package org.jboss.vergere.bootstrapper;

import static org.jboss.errai.codegen.meta.MetaClassFactory.parameterizedAs;
import static org.jboss.errai.codegen.meta.MetaClassFactory.typeParametersOf;
import static org.jboss.errai.codegen.util.Stmt.invokeStatic;

import org.jboss.errai.codegen.ArithmeticOperator;
import org.jboss.errai.codegen.Parameter;
import org.jboss.errai.codegen.Statement;
import org.jboss.errai.codegen.builder.AnonymousClassStructureBuilder;
import org.jboss.errai.codegen.builder.BlockBuilder;
import org.jboss.errai.codegen.builder.ClassStructureBuilder;
import org.jboss.errai.codegen.builder.ConstructorBlockBuilder;
import org.jboss.errai.codegen.builder.MethodBlockBuilder;
import org.jboss.errai.codegen.builder.impl.ClassBuilder;
import org.jboss.errai.codegen.builder.impl.ObjectBuilder;
import org.jboss.errai.codegen.meta.MetaClass;
import org.jboss.errai.codegen.meta.MetaClassFactory;
import org.jboss.errai.codegen.meta.MetaMethod;
import org.jboss.errai.codegen.meta.impl.java.JavaReflectionClass;
import org.jboss.errai.codegen.util.Arith;
import org.jboss.errai.codegen.util.If;
import org.jboss.errai.codegen.util.Refs;
import org.jboss.errai.codegen.util.Stmt;
import org.jboss.vergere.client.AnnotationComparator;
import org.jboss.vergere.client.QualifierEqualityFactory;
import org.jboss.vergere.client.QualifierUtil;
import org.jboss.vergere.util.ClassScanner;
import org.jboss.vergere.util.MetaDataScanner;
import org.jboss.vergere.util.VergereUtils;

import javax.enterprise.util.Nonbinding;
import javax.inject.Qualifier;
import java.io.File;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * @author Mike Brock
 */
public class QualifierEqualityFactoryGenerator {
  public String generate() {
    try {

      // Generate class source code
      return generateQualifierEqualityFactory();

      // return the fully qualified name of the class generated
    }
    catch (Throwable e) {
      // record sendNowWith logger that Map generation threw an exception
      throw new RuntimeException("error generating", e);
    }
  }

  private static final String COMPARATOR_MAP_VAR = "comparatorMap";

  private String generateQualifierEqualityFactory() {
    final StringBuilder printWriter = new StringBuilder();

    final ClassStructureBuilder<? extends ClassStructureBuilder<?>> builder
        = ClassBuilder.define(QualifierEqualityFactory.class.getName() + "Impl").publicScope()
        .implementsInterface(QualifierEqualityFactory.class)
        .body();

    builder.getClassDefinition().getContext().setPermissiveMode(true);

    final MetaClass mapStringAnnoComp
        = parameterizedAs(HashMap.class, typeParametersOf(String.class, AnnotationComparator.class));

    builder.privateField(COMPARATOR_MAP_VAR, mapStringAnnoComp)
        .initializesWith(Stmt.newObject(mapStringAnnoComp)).finish();

    final ConstructorBlockBuilder<? extends ClassStructureBuilder<?>> constrBuilder = builder.publicConstructor();

    final MetaDataScanner scanner = ClassScanner.getScanner();
    final Set<Class<?>> typesAnnotatedWith = scanner.getTypesAnnotatedWith(Qualifier.class);

    for (final Class<?> aClass : typesAnnotatedWith) {
      final MetaClass MC_annotationClass = MetaClassFactory.get(aClass);
      final Collection<MetaMethod> methods = getAnnotationAttributes(MC_annotationClass);

      if (methods.isEmpty()) continue;

      constrBuilder._(Stmt.loadVariable(COMPARATOR_MAP_VAR)
          .invoke("put", aClass.getName(), generateComparatorFor(MC_annotationClass, methods)));
    }

    // finish constructor
    constrBuilder.finish();

    final MetaClass annotationClazz = JavaReflectionClass.newUncachedInstance(Annotation.class);
    builder.publicMethod(boolean.class, "isEqual",
        Parameter.of(annotationClazz, "a1"), Parameter.of(annotationClazz, "a2"))
        .body()
        ._(If.cond(invokeStatic(QualifierUtil.class, "isSameType", Refs.get("a1"), Refs.get("a2")))
            ._(
                If.cond(Stmt.loadVariable(COMPARATOR_MAP_VAR).invoke("containsKey",
                    Stmt.loadVariable("a1").invoke("annotationType").invoke("getName")))
                    ._(Stmt.castTo(AnnotationComparator.class, Stmt.loadVariable(COMPARATOR_MAP_VAR)
                        .invoke("get", Stmt.loadVariable("a1").invoke("annotationType").invoke("getName"))
                    ).invoke("isEqual", Refs.get("a1"), Refs.get("a2")).returnValue())
                    .finish()
                    .else_()
                    ._(Stmt.load(true).returnValue())
                    .finish()
            )
            .finish()
            .else_()
            ._(Stmt.load(false).returnValue())
            .finish()).finish();


    builder.publicMethod(int.class, "hashCodeOf", Parameter.of(Annotation.class, "a1"))
        .body()
        ._(
            If.cond(Stmt.loadVariable(COMPARATOR_MAP_VAR).invoke("containsKey",
                Stmt.loadVariable("a1").invoke("annotationType").invoke("getName")))
                ._(Stmt.castTo(AnnotationComparator.class, Stmt.loadVariable(COMPARATOR_MAP_VAR)
                    .invoke("get", Stmt.loadVariable("a1").invoke("annotationType").invoke("getName"))
                ).invoke("hashCodeOf", Refs.get("a1")).returnValue())
                .finish()
                .else_()
                ._(Stmt.loadVariable("a1").invoke("annotationType").invoke("hashCode").returnValue())
                .finish()).finish();


    final String csq = builder.toJavaString();

    final File fileCacheDir = VergereUtils.getApplicationCacheDirectory();
    final File cacheFile = new File(fileCacheDir.getAbsolutePath() + "/" + builder.getClassDefinition().getName() + ".java");
    VergereUtils.writeStringToFile(cacheFile, csq);

    printWriter.append(csq);
    return printWriter.toString();
  }

  private static Collection<MetaMethod> getAnnotationAttributes(final MetaClass MC_annotationClass) {
    final List<MetaMethod> methods = new ArrayList<MetaMethod>();
    for (final MetaMethod method : MC_annotationClass.getDeclaredMethods()) {
      if (method.isAnnotationPresent(Nonbinding.class) || method.isPrivate() || method.isProtected()
          || method.getName().equals("equals") ||
          method.getName().equals("hashCode")) continue;

      methods.add(method);
    }
    return methods;
  }

  private Statement generateComparatorFor(final MetaClass MC_annotationClass, final Collection<MetaMethod> methods) {
    final MetaClass MC_annoComparator = parameterizedAs(AnnotationComparator.class, typeParametersOf(MC_annotationClass));

    final AnonymousClassStructureBuilder clsBuilder = ObjectBuilder.newInstanceOf(MC_annoComparator).extend();
    final MethodBlockBuilder<AnonymousClassStructureBuilder> isEqualBuilder = clsBuilder
        .publicMethod(boolean.class, "isEqual",
            Parameter.of(MC_annotationClass, "a1"), Parameter.of(MC_annotationClass, "a2"))
        .annotatedWith(new Override() {
          @Override
          public Class<? extends Annotation> annotationType() {
            return Override.class;
          }
        });

    for (final MetaMethod method : methods) {
      if (method.getReturnType().isPrimitive()) {
        isEqualBuilder._(
            If.notEquals(Stmt.loadVariable("a1").invoke(method), Stmt.loadVariable("a2").invoke(method))
                ._(Stmt.load(false).returnValue())
                .finish()
        );
      }
      else {
        isEqualBuilder._(
            If.not(Stmt.loadVariable("a1").invoke(method).invoke("equals", Stmt.loadVariable("a2").invoke(method)))
                ._(Stmt.load(false).returnValue())
                .finish()
        );
      }
    }

    isEqualBuilder._(Stmt.load(true).returnValue());

    final BlockBuilder<AnonymousClassStructureBuilder> hashCodeOfBuilder
        = clsBuilder.publicOverridesMethod("hashCodeOf", Parameter.of(MC_annotationClass, "a1"));

    hashCodeOfBuilder._(Stmt.declareVariable(int.class).named("hash")
        .initializeWith(Stmt.loadVariable("a1").invoke("annotationType").invoke("hashCode")));

    for (final MetaMethod method : methods) {
      hashCodeOfBuilder._(Stmt.loadVariable("hash")
          .assignValue(hashArith(method)));
    }

    hashCodeOfBuilder._(Stmt.loadVariable("hash").returnValue());

    hashCodeOfBuilder.finish();

    // finish method;
    final AnonymousClassStructureBuilder classStructureBuilder = isEqualBuilder.finish();

    return classStructureBuilder.finish();
  }

  private static Statement hashArith(final MetaMethod method) {
    return Arith.expr(
        Arith.expr(31, ArithmeticOperator.Multiplication, Refs.get("hash")),
        ArithmeticOperator.Addition,
        Stmt.invokeStatic(QualifierUtil.class, "hashValueFor", Stmt.loadVariable("a1").invoke(method))
    );
  }
}
