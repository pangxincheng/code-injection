package org.github.pangxincheng;

import org.github.pangxincheng.anno.CodeInjection;

import java.util.ArrayList;
import java.util.List;

public class CodeInjectionTest {

    @CodeInjection("org.github.pangxincheng.source.WrapperDemo:decorator")
    protected List<String> methodA(List<String> lst) {
        lst.add("methodA");
        return lst;
    }

    public static void main(String[] args) {
        List<String> lst = new CodeInjectionTest().methodA(new ArrayList<>());
        System.out.println(lst);
    }
}
