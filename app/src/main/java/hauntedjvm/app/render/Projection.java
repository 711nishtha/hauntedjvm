package hauntedjvm.app.render;

import java.util.ArrayList;
import java.util.List;

/**
 * A pinhole camera for the surveillance feeds.
 *
 * <p>World coordinates: {@code x} and {@code y} are map cells (one cell is one metre), {@code z}
 * is height above the floor. Polygons are clipped against the near plane in camera space before
 * projection, so walls that run past the lens are drawn correctly instead of flipping inside out.
 */
final class Projection {

    static final double NEAR = 0.08;

    private final double px;
    private final double py;
    private final double pz;
    private final double[] forward;
    private final double[] right;
    private final double[] up;
    private final double focal;
    private final double halfW;
    private final double halfH;

    Projection(double camX, double camY, double camZ, double targetX, double targetY, double targetZ,
               double horizontalFovDegrees, double width, double height) {
        this.px = camX;
        this.py = camY;
        this.pz = camZ;
        this.forward = normalise(new double[] {targetX - camX, targetY - camY, targetZ - camZ});
        this.right = normalise(cross(forward, new double[] {0, 0, 1}));
        this.up = cross(right, forward);
        this.halfW = width / 2;
        this.halfH = height / 2;
        this.focal = halfW / Math.tan(Math.toRadians(horizontalFovDegrees) / 2);
    }

    /** Camera-space coordinates: {right, up, depth}. */
    double[] toCamera(double x, double y, double z) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return new double[] {
            dx * right[0] + dy * right[1] + dz * right[2],
            dx * up[0] + dy * up[1] + dz * up[2],
            dx * forward[0] + dy * forward[1] + dz * forward[2]};
    }

    /** Screen position and depth of a camera-space point already in front of the near plane. */
    double[] toScreen(double[] c) {
        return new double[] {halfW + c[0] / c[2] * focal, halfH - c[1] / c[2] * focal, c[2]};
    }

    /** Projects a single world point, or returns {@code null} if it is behind the lens. */
    double[] project(double x, double y, double z) {
        double[] c = toCamera(x, y, z);
        return c[2] < NEAR ? null : toScreen(c);
    }

    double distance(double x, double y, double z) {
        return Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
    }

    /**
     * Clips a world-space polygon to the near plane and projects it.
     *
     * @param world vertices as {x, y, z} triples
     * @return screen polygon as parallel x and y arrays, or {@code null} if nothing is visible
     */
    double[][] polygon(double[][] world) {
        List<double[]> in = new ArrayList<>(world.length);
        for (double[] v : world) {
            in.add(toCamera(v[0], v[1], v[2]));
        }
        List<double[]> clipped = new ArrayList<>(in.size() + 2);
        for (int i = 0; i < in.size(); i++) {
            double[] a = in.get(i);
            double[] b = in.get((i + 1) % in.size());
            boolean aIn = a[2] >= NEAR;
            boolean bIn = b[2] >= NEAR;
            if (aIn) {
                clipped.add(a);
            }
            if (aIn != bIn) {
                double t = (NEAR - a[2]) / (b[2] - a[2]);
                clipped.add(new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, NEAR});
            }
        }
        if (clipped.size() < 3) {
            return null;
        }
        double[] xs = new double[clipped.size()];
        double[] ys = new double[clipped.size()];
        for (int i = 0; i < clipped.size(); i++) {
            double[] s = toScreen(clipped.get(i));
            xs[i] = s[0];
            ys[i] = s[1];
        }
        return new double[][] {xs, ys};
    }

    /** Whether a surface with outward normal (nx, ny, nz) at the given point faces the camera. */
    boolean facing(double x, double y, double z, double nx, double ny, double nz) {
        return (px - x) * nx + (py - y) * ny + (pz - z) * nz > 0;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalise(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / len, v[1] / len, v[2] / len};
    }
}
