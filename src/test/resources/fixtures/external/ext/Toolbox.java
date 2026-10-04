package ext;

import java.util.ArrayList;

public class Toolbox {
    public Helper build() {
        ArrayList<String> names = new ArrayList<>();
        names.add("x");
        Helper helper = new Helper("y");
        PlainPojo pojo = new PlainPojo();
        return helper;
    }
}
