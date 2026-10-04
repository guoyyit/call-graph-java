package acme;

import java.util.ArrayList;

public class Factory {
    public Widget make() {
        ArrayList<String> names = new ArrayList<>();
        names.add("x");
        return new Widget(names.size());
    }
}
