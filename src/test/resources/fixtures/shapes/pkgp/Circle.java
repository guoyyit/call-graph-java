package pkgp;

public class Circle extends AbstractShape {
    private final double radius;

    public Circle(double radius) {
        this.radius = radius;
    }

    @Override
    public double area() {
        return 3.14 * radius * radius;
    }

    @Override
    String name() {
        return "circle";
    }
}
