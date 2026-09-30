package pkgp;

public abstract class AbstractShape implements Shape {
    @Override
    public double area() {
        return 0;
    }

    abstract String name();
}
