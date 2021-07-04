package io.norberg.automatter.processor;

import static java.util.stream.Collectors.joining;
import static javax.lang.model.element.Modifier.DEFAULT;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import io.norberg.automatter.AutoMatter;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.TypeKindVisitor8;
import javax.lang.model.util.Types;

/**
 * Holds information about an automatter annotated interface and the entities it generates.
 */
class Descriptor {

  private final DeclaredType valueType;
  private final TypeElement valueTypeElement;
  private final List<? extends TypeMirror> valueTypeArguments;
  private final String packageName;
  private final String valueTypeName;
  private final String builderName;
  private final List<ExecutableElement> fields;
  private final Map<String, TypeMirror> fieldTypes;
  private final boolean isPublic;
  private final String concreteBuilderName;
  private final String fullyQualifiedBuilderName;
  private final List<Descriptor> superValueTypes;
  private boolean isGeneric;
  private boolean toBuilder;
  private ExecutableElement toString;
  private ExecutableElement check;

  static Descriptor of(final Element element, final Elements elements, final Types types)
      throws AutoMatterProcessorException {
    if (!element.getKind().isInterface()) {
      throw new AutoMatterProcessorException("@AutoMatter target must be an interface", element);
    }
    final DeclaredType valueType = (DeclaredType) element.asType();
    return new Descriptor(valueType, elements, types);
  }

  Descriptor(final DeclaredType valueType, final Elements elements, final Types types)
      throws AutoMatterProcessorException {
    this.valueType = valueType;
    this.valueTypeElement = (TypeElement) valueType.asElement();
    this.valueTypeArguments = valueType.getTypeArguments();
    this.valueTypeName = nestedName(valueTypeElement, elements);
    this.isGeneric = !valueTypeArguments.isEmpty();
    this.packageName = elements.getPackageOf(valueTypeElement).getQualifiedName().toString();
    this.builderName = valueTypeElement.getSimpleName().toString() + "Builder";
    final String typeParameterization = isGeneric ?
        "<" + valueTypeArguments.stream().map(TypeMirror::toString).collect(joining(",")) + ">"
        : "";
    this.concreteBuilderName = builderName + typeParameterization;
    this.fullyQualifiedBuilderName = fullyQualifedName(packageName, concreteBuilderName);
    this.fields = new ArrayList<>();
    this.fieldTypes = new LinkedHashMap<>();
    this.isPublic = valueTypeElement.getModifiers().contains(PUBLIC);
    this.toString = findInstanceMethod(valueTypeElement, AutoMatter.ToString.class);
    this.check = findInstanceMethod(valueTypeElement, AutoMatter.Check.class);
    this.superValueTypes = enumerateSuperValueTypes(elements, types);
    enumerateFields(types);
  }

  private List<Descriptor> enumerateSuperValueTypes(Elements elements, Types types) {
    final List<Descriptor> superValueTypes = new ArrayList<>();
    enumerateSuperValueTypes(valueType, elements, types, superValueTypes);
    return Collections.unmodifiableList(superValueTypes);
  }

  private void enumerateSuperValueTypes(DeclaredType valueType, Elements elements,
      Types types, List<Descriptor> superValueTypes) {
    for (final TypeMirror superType : types.directSupertypes(valueType)) {
      if (superType.getKind() != TypeKind.DECLARED) {
        continue;
      }
      final DeclaredType superValueType = (DeclaredType) superType;
      final TypeElement superValueTypeElement = (TypeElement) superValueType.asElement();
      if (superValueTypeElement.getKind() != ElementKind.INTERFACE) {
        continue;
      }
      enumerateSuperValueTypes(superValueType, elements, types, superValueTypes);
      if (superValueTypeElement.getAnnotation(AutoMatter.class) != null) {
        superValueTypes.add(new Descriptor(superValueType, elements, types));
      }
    }
  }

  Optional<ExecutableElement> toStringMethod() {
    return Optional.ofNullable(toString);
  }

  Optional<ExecutableElement> checkMethod() {
    return Optional.ofNullable(check);
  }


  public List<Descriptor> superValueTypes() {
    return superValueTypes;
  }

  private static String nestedName(final TypeElement element, final Elements elements) {
    final String qualifiedName = element.getQualifiedName().toString();
    final String packageName = elements.getPackageOf(element).getQualifiedName().toString();
    if (packageName.isEmpty()) {
      return qualifiedName;
    }
    return qualifiedName.substring(packageName.length() + 1);
  }

  private void enumerateFields(final Types types) {
    final List<ExecutableElement> methods = methods(valueTypeElement);
    for (final ExecutableElement method : methods) {
      if (isStaticOrDefaultOrPrivate(method)) {
        continue;
      }
      if (method.getSimpleName().toString().equals("builder")) {
        final TypeMirror returnType = (method).getReturnType();
        // TODO: javac does not seem to want to provide the name of the return type if it is not yet present and generic
        if (!isGeneric &&
            !returnType.toString().equals(concreteBuilderName) &&
            !returnType.toString().equals(fullyQualifiedBuilderName)) {
          throw new AutoMatterProcessorException(
              "builder() return type must be " + concreteBuilderName, valueTypeElement);
        }
        toBuilder = true;
        continue;
      }

      verifyResolved(method.getReturnType());

      fields.add(method);

      // Resolve inherited members
      final ExecutableType methodType = (ExecutableType) types.asMemberOf(valueType, method);
      final TypeMirror fieldType = methodType.getReturnType();

      // Resolve types
      fieldTypes.put(method.getSimpleName().toString(), fieldType);
    }
  }

  private List<ExecutableElement> methods(final TypeElement element) {
    final Map<String, ExecutableElement> methodMap = new LinkedHashMap<>();
    enumerateMethods(element, methodMap);
    return new ArrayList<>(methodMap.values());
  }

  private void enumerateMethods(final TypeElement element,
      final Map<String, ExecutableElement> methods) {
    for (final TypeMirror interfaceType : element.getInterfaces()) {
      final TypeElement interfaceElement = (TypeElement) ((DeclaredType) interfaceType).asElement();
      enumerateMethods(interfaceElement, methods);
    }
    for (final Element member : element.getEnclosedElements()) {
      if (member.getKind() != ElementKind.METHOD) {
        continue;
      }
      methods.put(member.getSimpleName().toString(), (ExecutableElement) member);
    }
  }

  private ExecutableElement findInstanceMethod(final TypeElement element,
      final Class<? extends Annotation> tag) {
    final List<ExecutableElement> matches = new ArrayList<>();
    for (final Element member : element.getEnclosedElements()) {
      if (member.getKind() == ElementKind.METHOD && member.getAnnotation(tag) != null) {
        if (!member.getModifiers().contains(STATIC) && !member.getModifiers().contains(DEFAULT)) {
          throw new AutoMatterProcessorException(
              "Method annotated with @AutoMatter." + tag.getSimpleName()
                  + " must be static or default", valueTypeElement);
        }
        matches.add((ExecutableElement) member);
      }
    }
    if (matches.size() == 1) {
      return matches.get(0);
    } else if (matches.size() > 1) {
      throw new AutoMatterProcessorException(
          "There must only be one @AutoMatter." + tag.getSimpleName()
              + "annotated method on a type", valueTypeElement);
    }
    for (final TypeMirror interfaceType : element.getInterfaces()) {
      final TypeElement interfaceElement = (TypeElement) ((DeclaredType) interfaceType).asElement();
      final ExecutableElement method = findInstanceMethod(interfaceElement, tag);
      if (method != null) {
        return method;
      }
    }
    return null;
  }

  private static boolean isStaticOrDefaultOrPrivate(final Element member) {
    final Set<Modifier> modifiers = member.getModifiers();
    return modifiers.contains(STATIC)
        || modifiers.contains(DEFAULT)
        || modifiers.contains(PRIVATE);
  }

  String packageName() {
    return this.packageName;
  }

  String builderName() {
    return this.builderName;
  }

  String valueTypeName() {
    return this.valueTypeName;
  }

  boolean isPublic() {
    return this.isPublic;
  }

  boolean isGeneric() {
    return this.isGeneric;
  }

  List<ExecutableElement> fields() {
    return fields;
  }

  TypeName fieldTypeName(ExecutableElement field) {
    return TypeName.get(fieldType(field));
  }

  TypeMirror fieldType(ExecutableElement field) {
    return fieldTypes.get(field.getSimpleName().toString());
  }

  boolean hasToBuilder() {
    return this.toBuilder;
  }

  private static String fullyQualifedName(final String packageName, final String simpleName) {
    return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }

  List<TypeVariableName> typeVariables() {
    final List<TypeVariableName> variables = new ArrayList<>();
    if (isGeneric) {
      for (final TypeMirror argument : valueTypeArguments) {
        if (argument instanceof TypeVariable) {
          final TypeVariable typeVariable = (TypeVariable) argument;
          variables.add(TypeVariableName.get(typeVariable));
        }
      }
    }
    return variables;
  }

  TypeName[] typeArguments() {
    final List<TypeName> variables = new ArrayList<>();
    if (isGeneric) {
      for (final TypeMirror argument : valueTypeArguments) {
        variables.add(TypeVariableName.get(argument));
      }
    }
    return variables.toArray(new TypeName[0]);
  }

  private static void verifyResolved(TypeMirror type) {
    type.accept(new TypeKindVisitor8<Void, Void>() {
      @Override
      public Void visitIntersection(IntersectionType t, Void aVoid) {
        t.getBounds().forEach(Descriptor::verifyResolved);
        return null;
      }

      @Override
      public Void visitUnion(UnionType t, Void aVoid) {
        t.getAlternatives().forEach(Descriptor::verifyResolved);
        return null;
      }

      @Override
      public Void visitArray(ArrayType t, Void aVoid) {
        verifyResolved(t.getComponentType());
        return null;
      }

      @Override
      public Void visitError(ErrorType t, Void aVoid) {
        throw new UnresolvedTypeException(t.toString());
      }

      @Override
      public Void visitTypeVariable(TypeVariable t, Void aVoid) {
        verifyResolved(t.getLowerBound());
        verifyResolved(t.getUpperBound());
        return null;
      }

      @Override
      public Void visitWildcard(WildcardType t, Void aVoid) {
        final TypeMirror extendsBound = t.getExtendsBound();
        if (extendsBound != null) {
          verifyResolved(extendsBound);
        }
        final TypeMirror superBound = t.getSuperBound();
        if (superBound != null) {
          verifyResolved(superBound);
        }
        return null;
      }
    }, null);
  }
}
