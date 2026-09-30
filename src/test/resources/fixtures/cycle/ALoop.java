package cycle;

public class ALoop {
    public void a(BLoop b) {
        b.b(this);
    }
}
