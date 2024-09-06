## CodeInjection
### 背景
实习期间碰到了一个需求, 需要对一个已有的项目进行功能扩展, 但是需要改动的代码比较多, 而且改动的代码其实是通用的, 为了
避免大量的改动, 使用了APT技术对源代码进行了注入。
### 简介
这个Demo是基于APT的技术的代码注入, 相比于CGLib/JDK动态代理, 基于APT的代码注入是在编译期对源代码的抽象
语法树进行修改, 从而实现对源代码的修改。 在执行期间, 注入的代码已经被编译到了目标类中, 从而避免了动态代理
的性能损耗。

### 代码注入的原理
代码注入的原理是基于APT的技术, APT是Annotation Processing Tool的缩写, 是一种处理注解的工具, 它对源代码
进行检测并进行编译时的处理。APT的工作流程如下:
1. 编译器在编译源代码的时候, 会调用APT工具, 读取源代码中的注解信息。
2. APT工具会调用对应的注解处理器, 处理注解。
3. 注解处理器可以获取到源代码的抽象语法树(Abstrace Syntax Tree, AST), Java提供了Javac Tree API来操作
   AST。在这个Demo中, 我们使用了Javac Tree API来操作AST。
4. 注解处理器处理完成注解后，javac会将处理后的AST编译成class文件。

### Quick Start
假设我们有一个方法`methodA`, 我们希望方法`methodA`调用之后插入一段代码, 从而实现对methodA的返回
结果进行处理。
在Python中，我们可以使用装饰器来实现这个功能，一个简单的例子如下:
```python
# main.py
def decorator(func):
    def wrapper(*args, **kwargs):
        result = func(*args, **kwargs)
        result.append("decorator")
        return result
    return wrapper
    
@decorator
def methodA(list):
    list.append("methodA")
    return list
    
def main():
    result = methodA([])
    print(result)

if __name__ == "__main__":
    main()
```
上述代码的执行结果是`['methodA', 'decorator']`。

在Java中，我们可以使用我们自己实现的注解处理器来实现相同的功能，例子如下：

```java
// Main.java
package main;
import org.github.pangxincheng.anno.CodeInjection;

public class Main {
   @CodeInjection("main.source.WrapperDemo:decorator")
   public void methodA(List<String> list) {
      list.add("methodA");
   }

   public static void main(String[] args) {
      List<String> list = new ArrayList<>();
      (new Main()).methodA(list);
      System.out.println(list);
   }
}
```
```java
// Source.java
package main.source;

import java.util.List;

public class WrapperDemo {

   public List<String> decorator(List<String> lst) {
      lst.add("decorator");
      return lst;
   }
}
```


### 运行Demo
1. 使用`mvn clean compile`编译代码
2. 运行`org.github.pangxincheng.CodeInjectionTest`类的`main`方法

### 遇到的坑
1. jvm共享虚拟机选项需要设置`-Djps.track.ap.dependencies=false`
2. 由于APT是在编译期进行处理的, 所以在使用CodeInjection的时候, 需要先把注解处理器编译成.class文件, 
然后再编译使用了注解的代码。否则会报错。
3. 修改抽象语法树的时候总是会出现一些奇奇怪怪的问题, 这个时候可以检查一下符号表, 尽可能不要从另外一个类
的方法中直接复制body, 这样会导致符号表的混乱，我最开始实现的时候是把`WrapperDemo`的`decorator`方法
的body直接复制到了`methodA`的body中, 结果一直报错(在公司的代码中总报错, 在Demo中不报错, 难顶奥)。后
来改成了直接调用`decorator`方法, 就不报错了。
4. 可以断点调试注解处理器, 方法是在编译使用注解代码的时候, 使用`mvnDebug clean compile`进行编译, 然
后使用IDEA的远程JVM调试功能进行调试，端口指定为`8000`(`mvnDebug clean compile`默认使用`8000`端口， 当
然也可以自定义)。