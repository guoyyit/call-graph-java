package cycle;

public class BLoop {
    public void b(ALoop a) {
        a.a(this);
    }

    public void self() {
        self();
    }
}
