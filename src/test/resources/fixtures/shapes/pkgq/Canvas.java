package pkgq;

import pkgp.Shape;
import pkgp.Circle;

public class Canvas {
    public double render(Shape shape) {
        return shape.area();
    }

    public Shape makeShape() {
        Circle circle = new Circle(2.0);
        return circle;
    }
}
