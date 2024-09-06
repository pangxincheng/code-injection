package org.github.pangxincheng.processor;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.*;
import org.github.pangxincheng.anno.CodeInjection;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.Set;
import java.util.stream.Collectors;

@AutoService(Processor.class)
@SupportedAnnotationTypes(value = {"org.github.pangxincheng.anno.CodeInjection"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class CodeInjectionProcessor extends AbstractProcessor {

    private JavacTrees trees;
    private Messager messager;
    private Names names;
    private TreeMaker treeMaker;
    private Elements elements;
    private Symtab symtab;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.trees = JavacTrees.instance(processingEnv);
        this.messager = processingEnv.getMessager();
        final Context context = ((JavacProcessingEnvironment)processingEnv).getContext();
        this.names = Names.instance(context);
        this.treeMaker = TreeMaker.instance(context);
        this.elements = processingEnv.getElementUtils();
        this.symtab = Symtab.instance(context);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(CodeInjection.class)) {
            if (element.getKind() != ElementKind.METHOD) {
                this.messager.printMessage(Diagnostic.Kind.ERROR, "Only method can be annotated with @CodeInjection");
                return true;
            }

            // get target Element
            ExecutableElement tgtElement = (ExecutableElement)element;

            // get source Element
            CodeInjection anno = tgtElement.getAnnotation(CodeInjection.class);
            String source = anno.value();
            ExecutableElement srcElement = getMethodByPath(source);
            if (srcElement == null) {
                this.messager.printMessage(Diagnostic.Kind.ERROR, String.format("Method %s not found", source));
                return true;
            }

            // get the abstract syntax tree of 1. target method 2. target class 3. target compilation unit
            JCTree.JCMethodDecl tgtMethod = this.trees.getTree(tgtElement);
            JCTree.JCClassDecl tgtClass = this.trees.getTree((TypeElement)tgtElement.getEnclosingElement());
            JCTree.JCCompilationUnit tgtCompilationUnit = (JCTree.JCCompilationUnit)this.trees.getPath(tgtElement)
                    .getCompilationUnit();

            // get the abstract syntax tree of 1. source method 2. source class 3. source compilation unit
            JCTree.JCMethodDecl srcMethod = this.trees.getTree(srcElement);
            JCTree.JCClassDecl srcClass = this.trees.getTree((TypeElement)srcElement.getEnclosingElement());
            JCTree.JCCompilationUnit srcCompilationUnit = (JCTree.JCCompilationUnit)this.trees.getPath(srcElement)
                    .getCompilationUnit();

            if (srcMethod.params.size() != 1
                    || !srcMethod.params.get(0).vartype.type.toString().equals(tgtMethod.restype.type.toString())
                    || !srcMethod.restype.type.toString().equals(tgtMethod.restype.type.toString())
            ) {
                this.messager.printMessage(Diagnostic.Kind.ERROR,
                        String.format("Method %s has invalid signature", source));
                return true;
            }

            if (!updateImport(tgtCompilationUnit, srcCompilationUnit.getPackageName(), srcClass.getSimpleName())) {
                this.messager.printMessage(Diagnostic.Kind.ERROR,
                        String.format("Updating import failed for method %s", source));
                return true;
            }

            if (!updateMethod(tgtClass, srcMethod, tgtMethod)) {
                this.messager.printMessage(Diagnostic.Kind.ERROR,
                        String.format("Updating method %s failed", tgtElement.getSimpleName()));
                return true;
            }
            // ...
        }
        return false;
    }

    private ExecutableElement getMethodByPath(String path) {
        try {
            String classFullName = path.substring(0, path.lastIndexOf(":"));
            String methodName = path.substring(path.lastIndexOf(":") + 1);
            TypeElement typeElement = this.elements.getTypeElement(classFullName);
            if (typeElement == null) {
                return null;
            }
            for (Element element : typeElement.getEnclosedElements()) {
                if (element.getKind() == ElementKind.METHOD && element.getSimpleName().toString().equals(methodName)) {
                    return (ExecutableElement) element;
                }
            }
        } catch (Exception e) {
            this.messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage());
        }
        return null;
    }

    private boolean updateImport(JCTree.JCCompilationUnit tgtCompilationUnit, JCTree.JCExpression srcPackageName, Name srcClassName) {
        // <srcPackageName>.<srcClassName>
        JCTree.JCFieldAccess importExpression = treeMaker.Select(srcPackageName, srcClassName);
        // import <srcPackageName>.<srcClassName>;
        JCTree.JCImport newImport = treeMaker.Import(importExpression, false);

        // check if the import already exists
        boolean exists = tgtCompilationUnit.defs.stream().anyMatch(def -> {
            if (def instanceof JCTree.JCImport) {
                JCTree.JCImport importDef = (JCTree.JCImport)def;
                return importDef.qualid.toString().equals(importExpression.toString());
            }
            return false;
        });

        // if the import does not exist, add it to the compilation unit
        if (!exists) {
            tgtCompilationUnit.defs = tgtCompilationUnit.defs.prepend(newImport);
        }
        return true;
    }

    private boolean updateMethod(JCTree.JCClassDecl tgtClass, JCTree.JCMethodDecl srcMethod, JCTree.JCMethodDecl tgtMethod) {
        // create a wrapper method, the function of the wrapper method is same as the target method
        JCTree.JCMethodDecl wrapperMethod = createWrapperMethod(tgtMethod, "wrapper_" + tgtMethod.name.toString());

        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<>();

        // create the abstract syntax tree of
        // `wrapper_<tgtMethod>(...)`
        JCTree.JCMethodInvocation callWrapper = this.treeMaker.Apply(
                List.nil(),
                this.treeMaker.Ident(wrapperMethod.name),
                List.from(
                        wrapperMethod.params
                                .stream()
                                .map(param -> this.treeMaker.Ident(param.name))
                                .collect(Collectors.toList())
                )
        );

        // create the abstract syntax tree of
        // `final <wrapperMethod.restype> <randomName> = wrapper_<tgtMethod>(...);`
        JCTree.JCVariableDecl varDecl = this.treeMaker.VarDef(
                this.treeMaker.Modifiers(Flags.FINAL),
                this.names.fromString(genRandomName()),
                wrapperMethod.restype,
                callWrapper
        );
        statements.append(varDecl);

        // create the abstract syntax tree of
        // `return new <srcMethod.owner>().<srcMethod.name>(<randomName>);`
        JCTree.JCReturn returnIdent = this.treeMaker.Return(
                // new <srcClass>().<srcMethod>(<randomName>)
                this.treeMaker.Apply(
                        List.nil(),
                        this.treeMaker.Select(
                                this.treeMaker.NewClass(
                                        null,
                                        List.nil(),
                                        this.treeMaker.Ident(srcMethod.sym.owner.name),
                                        List.nil(),
                                        null
                                ),
                                srcMethod.name
                        ),
                        List.of(this.treeMaker.Ident(varDecl.name))
                )
        );
        statements.append(returnIdent);

        tgtMethod.body = this.treeMaker.Block(0, statements.toList());
        tgtClass.defs = tgtClass.defs.prepend(wrapperMethod);
        tgtClass.sym.members_field.enterIfAbsent(wrapperMethod.sym);
        return true;
    }

    private JCTree.JCMethodDecl createWrapperMethod(JCTree.JCMethodDecl rawMethod, String wrapperMethodName) {
        JCTree.JCMethodDecl wrapperMethod = this.treeMaker.MethodDef(
                this.treeMaker.Modifiers(Flags.PRIVATE),
                this.names.fromString(wrapperMethodName),
                rawMethod.restype,
                rawMethod.typarams,
                rawMethod.recvparam,
                rawMethod.params,
                rawMethod.thrown,
                rawMethod.body,
                rawMethod.defaultValue
        );
        wrapperMethod.sym = new Symbol.MethodSymbol(
                Flags.PRIVATE,
                wrapperMethod.name,
                new Type.MethodType(
                        List.from(rawMethod.params.stream().map(param -> param.vartype.type).collect(Collectors.toList())),
                        rawMethod.restype.type,
                        List.from(rawMethod.thrown.stream().map(throwable -> throwable.type).collect(Collectors.toList())),
                        this.symtab.methodClass
                ),
                rawMethod.sym.owner
        );
        return wrapperMethod;
    }

    private String genRandomName() {
        return "arg" + System.currentTimeMillis();
    }
}
