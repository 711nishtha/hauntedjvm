package hauntedjvm.core.world;

/**
 * Where a surveillance camera hangs.
 *
 * @param mount the interior corner cell the camera is fixed above
 * @param hidden cameras that do not exist until the simulation creates them
 */
public record CameraMount(String code, String roomCode, Cell mount, Corner corner, boolean hidden) {

    public enum Corner { NW, NE, SW, SE }
}
