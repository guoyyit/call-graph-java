package pkgp;

public class Square extends AbstractShape {
    private final double side;

    public Square(double side) {
        this.side = side;
    }

    @Override
    public double area() {
        return side * side;
    }

    @Override
    String name() {
        return "square";
    }
}
