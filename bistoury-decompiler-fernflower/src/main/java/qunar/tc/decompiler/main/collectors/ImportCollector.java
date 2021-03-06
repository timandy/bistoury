/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package qunar.tc.decompiler.main.collectors;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import qunar.tc.decompiler.main.ClassesProcessor;
import qunar.tc.decompiler.main.DecompilerContext;
import qunar.tc.decompiler.struct.StructClass;
import qunar.tc.decompiler.struct.StructContext;
import qunar.tc.decompiler.struct.StructField;
import qunar.tc.decompiler.util.TextBuffer;

import java.util.*;

public class ImportCollector {
    private static final String JAVA_LANG_PACKAGE = "java.lang";

    private final Map<String, String> mapSimpleNames = new HashMap<>();
    private final Set<String> setNotImportedNames = new HashSet<>();
    // set of field names in this class and all its predecessors.
    private final Set<String> setFieldNames = new HashSet<>();
    private final String currentPackageSlash;
    private final String currentPackagePoint;

    public ImportCollector(ClassesProcessor.ClassNode root) {
        String clName = root.classStruct.qualifiedName;
        int index = clName.lastIndexOf('/');
        if (index >= 0) {
            String packageName = clName.substring(0, index);
            currentPackageSlash = packageName + '/';
            currentPackagePoint = packageName.replace('/', '.');
        } else {
            currentPackageSlash = "";
            currentPackagePoint = "";
        }

        Map<String, StructClass> classes = DecompilerContext.getStructContext().getClasses();
        StructClass currentClass = root.classStruct;
        while (currentClass != null) {
            // all field names for the current class ..
            for (StructField f : currentClass.getFields()) {
                setFieldNames.add(f.getName());
            }

            // .. and traverse through parent.
            currentClass = currentClass.superClass != null ? classes.get(currentClass.superClass.getString()) : null;
        }
    }

    /**
     * Check whether the package-less name ClassName is shaded by variable in a context of
     * the decompiled class
     *
     * @param classToName - pkg.name.ClassName - class to find shortname for
     * @return ClassName if the name is not shaded by local field, pkg.name.ClassName otherwise
     */
    public String getShortNameInClassContext(String classToName) {
        String shortName = getShortName(classToName);
        if (setFieldNames.contains(shortName)) {
            return classToName;
        } else {
            return shortName;
        }
    }

    public String getShortName(String fullName) {
        return getShortName(fullName, true);
    }

    public String getShortName(String fullName, boolean imported) {
        ClassesProcessor.ClassNode node = DecompilerContext.getClassProcessor().getMapRootClasses().get(fullName.replace('.', '/')); //todo[r.sh] anonymous classes?

        String result = null;
        if (node != null && node.classStruct.isOwn()) {
            result = node.simpleName;

            while (node.parent != null && node.type == ClassesProcessor.ClassNode.CLASS_MEMBER) {
                //noinspection StringConcatenationInLoop
                result = node.parent.simpleName + '.' + result;
                node = node.parent;
            }

            if (node.type == ClassesProcessor.ClassNode.CLASS_ROOT) {
                fullName = node.classStruct.qualifiedName;
                fullName = fullName.replace('/', '.');
            } else {
                return result;
            }
        } else {
            fullName = fullName.replace('$', '.');
        }

        String shortName = fullName;
        String packageName = "";

        int lastDot = fullName.lastIndexOf('.');
        if (lastDot >= 0) {
            shortName = fullName.substring(lastDot + 1);
            packageName = fullName.substring(0, lastDot);
        }

        StructContext context = DecompilerContext.getStructContext();

        // check for another class which could 'shadow' this one. Two cases:
        // 1) class with the same short name in the current package
        // 2) class with the same short name in the default package
        boolean existsDefaultClass =
                (context.getClass(currentPackageSlash + shortName) != null && !packageName.equals(currentPackagePoint)) || // current package
                        (context.getClass(shortName) != null && !currentPackagePoint.isEmpty());  // default package

        if (existsDefaultClass ||
                (mapSimpleNames.containsKey(shortName) && !packageName.equals(mapSimpleNames.get(shortName)))) {
            //  don't return full name because if the class is a inner class, full name refers to the parent full name, not the child full name
            return result == null ? fullName : (packageName + "." + result);
        } else if (!mapSimpleNames.containsKey(shortName)) {
            mapSimpleNames.put(shortName, packageName);
            if (!imported) {
                setNotImportedNames.add(shortName);
            }
        }

        return result == null ? shortName : result;
    }

    public int writeImports(TextBuffer buffer) {
        int importLinesWritten = 0;

        List<String> imports = packImports();

        for (String s : imports) {
            buffer.append("import ");
            buffer.append(s);
            buffer.append(';');
            buffer.appendLineSeparator();

            importLinesWritten++;
        }

        return importLinesWritten;
    }

    private List<String> packImports() {

        /*return mapSimpleNames.entrySet().stream()
                .filter(ent ->
                        // exclude the current class or one of the nested ones
                        // empty, java.lang and the current packages
                        !setNotImportedNames.contains(ent.getKey()) &&
                                !ent.getValue().isEmpty() &&
                                !JAVA_LANG_PACKAGE.equals(ent.getValue()) &&
                                !ent.getValue().equals(currentPackagePoint)
                )
                .sorted(Map.Entry.<String, String>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(ent -> ent.getValue() + "." + ent.getKey())
                .collect(Collectors.toList());*/
        HashSet<Map.Entry<String, String>> set = Sets.newHashSet(mapSimpleNames.entrySet());
        Sets.filter(set, new Predicate<Map.Entry<String, String>>() {
            @Override
            public boolean apply(Map.Entry<String, String> ent) {
                return !setNotImportedNames.contains(ent.getKey()) &&
                        !ent.getValue().isEmpty() &&
                        !JAVA_LANG_PACKAGE.equals(ent.getValue()) &&
                        !ent.getValue().equals(currentPackagePoint);
            }
        });
        ArrayList<Map.Entry<String, String>> list = Lists.newArrayList(set);
        Collections.sort(list, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> c1, Map.Entry<String, String> c2) {
                int res = c1.getValue().compareTo(c2.getValue());
                return (res != 0) ? res : c1.getKey().compareTo(c2.getKey());
            }
        });
        List<String> result = Lists.newArrayList();
        for (Map.Entry<String, String> entry : list) {
            result.add(entry.getValue() + "." + entry.getKey());
        }
        return result;
    }
}