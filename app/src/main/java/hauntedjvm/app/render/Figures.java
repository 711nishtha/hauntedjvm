package hauntedjvm.app.render;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;

/**
 * Human silhouettes as a security camera would resolve them: no faces, just posture. Drawn in
 * screen space from a projected foot point and head height.
 */
final class Figures {

    /** How a figure should look. {@code eyes} draws eyeshine: the figure is facing the lens. */
    record Style(Color body, Color rim, boolean eyes, Color eyeColor, double widthFactor) {
    }

    private Figures() {
    }

    /**
     * @param footX  screen x of the feet
     * @param footY  screen y of the feet
     * @param height screen height of the figure
     * @param stride walk phase in [0, 1); {@code -1} standing still
     */
    static void draw(GraphicsContext g, double footX, double footY, double height, double stride, Style style) {
        if (height < 3) {
            return;
        }
        double w = height * style.widthFactor();
        double headR = height * 0.075;
        double headY = footY - height + headR;
        double shoulderY = footY - height * 0.80;
        double hipY = footY - height * 0.47;
        double swing = stride < 0 ? 0 : Math.sin(stride * Math.PI * 2) * w * 0.22;

        g.setFill(style.body());
        // Legs.
        g.fillRoundRect(footX - w * 0.30 + swing, hipY, w * 0.26, footY - hipY, w * 0.1, w * 0.1);
        g.fillRoundRect(footX + w * 0.04 - swing, hipY, w * 0.26, footY - hipY, w * 0.1, w * 0.1);
        // Torso, tapering from shoulders to hips.
        g.fillPolygon(
                new double[] {footX - w / 2, footX + w / 2, footX + w * 0.36, footX - w * 0.36},
                new double[] {shoulderY, shoulderY, hipY + height * 0.03, hipY + height * 0.03}, 4);
        g.fillOval(footX - w / 2, shoulderY - w * 0.12, w, w * 0.3);
        // Arms hang at the sides.
        g.fillRoundRect(footX - w * 0.62, shoulderY, w * 0.18, height * 0.34, w * 0.1, w * 0.1);
        g.fillRoundRect(footX + w * 0.44, shoulderY, w * 0.18, height * 0.34, w * 0.1, w * 0.1);
        // Neck and head.
        g.fillRect(footX - w * 0.09, headY + headR * 0.8, w * 0.18, shoulderY - headY - headR * 0.6);
        g.fillOval(footX - headR, headY - headR, headR * 2, headR * 2.2);

        if (style.rim() != null) {
            g.setStroke(style.rim());
            g.setLineWidth(Math.max(0.6, height / 140));
            g.strokeArc(footX - headR, headY - headR, headR * 2, headR * 2.2, 40, 110, ArcType.OPEN);
            g.strokeLine(footX + w / 2, shoulderY, footX + w * 0.36, hipY);
        }
        if (style.eyes()) {
            double eyeR = Math.max(0.8, headR * 0.16);
            g.setFill(style.eyeColor());
            g.fillOval(footX - headR * 0.45 - eyeR, headY - eyeR * 0.2, eyeR * 2, eyeR * 1.4);
            g.fillOval(footX + headR * 0.45 - eyeR, headY - eyeR * 0.2, eyeR * 2, eyeR * 1.4);
        }
    }

    /** Screen bounds of a figure, for hit testing. */
    static Hit bounds(hauntedjvm.core.entity.EntityId id, double footX, double footY, double height, double widthFactor) {
        double w = height * widthFactor * 1.3;
        return new Hit(id, footX - w / 2, footY - height, w, height);
    }
}
