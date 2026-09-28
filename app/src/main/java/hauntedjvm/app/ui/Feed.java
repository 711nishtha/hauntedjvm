package hauntedjvm.app.ui;

/** What the main monitor is showing. */
public sealed interface Feed {

    String label();

    /** The badge tracker's floor plan. */
    record Floorplan() implements Feed {
        @Override
        public String label() {
            return "FLOORPLAN";
        }
    }

    /** One surveillance camera. */
    record Camera(String code) implements Feed {
        @Override
        public String label() {
            return code;
        }
    }

    /** The facility process table. */
    record SystemTable() implements Feed {
        @Override
        public String label() {
            return "SYSTEM";
        }
    }
}
