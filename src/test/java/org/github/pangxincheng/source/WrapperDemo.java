package org.github.pangxincheng.source;

import java.util.List;

public class WrapperDemo {

    public List<String> decorator(List<String> lst) {
        lst.add("decorator");
        return lst;
    }
}
