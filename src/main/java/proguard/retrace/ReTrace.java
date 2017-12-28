/*
 * ProGuard -- shrinking, optimization, obfuscation, and preverification
 *             of Java bytecode.
 *
 * Copyright (c) 2002-2014 Eric Lafortune (eric@graphics.cornell.edu)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package proguard.retrace;

import proguard.classfile.util.ClassUtil;
import proguard.obfuscate.*;

import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Tool for de-obfuscating stack traces of applications that were obfuscated with ProGuard.
 *
 * @author Eric Lafortune, modified by F43nd1r
 */
@SuppressWarnings("WeakerAccess")
public class ReTrace implements MappingProcessor {
    private static final String REGEX_OPTION = "-regex";
    private static final String VERBOSE_OPTION = "-verbose";


    public static final String STACK_TRACE_EXPRESSION = "(?:.*?\\bat\\s+%c\\.%m\\s*\\(.*?(?::%l)?\\)\\s*)|(?:(?:.*?[:\"]\\s+)?%c(?::.*)?)";

    private static final String REGEX_CLASS = "\\b(?:[A-Za-z0-9_$]+\\.)*[A-Za-z0-9_$]+\\b";
    private static final String REGEX_CLASS_SLASH = "\\b(?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+\\b";
    private static final String REGEX_LINE_NUMBER = "\\b[0-9]+\\b";
    private static final String REGEX_TYPE = REGEX_CLASS + "(?:\\[\\])*";
    private static final String REGEX_MEMBER = "<?\\b[A-Za-z0-9_$]+\\b>?";
    private static final String REGEX_ARGUMENTS = "(?:" + REGEX_TYPE + "(?:\\s*,\\s*" + REGEX_TYPE + ")*)?";

    // The class settings.
    private final String regularExpression;
    private final boolean verbose;
    private final Reader mapping;
    private final Reader stackTrace;
    private final Writer output;

    private final Map<String, String> classMap = new HashMap<>();
    private final Map<String, Map<String, Set<FieldInfo>>> classFieldMap = new HashMap<>();
    private final Map<String, Map<String, Set<MethodInfo>>> classMethodMap = new HashMap<>();


    /**
     * Creates a new ReTrace object to process a stack trace from the given file, based on the given mapping file.
     *
     * @param regularExpression the regular expression for parsing the lines in the stack trace.
     * @param verbose           specifies whether the de-obfuscated stack trace should be verbose.
     * @param mapping           the mapping file that was written out by ProGuard.
     * @param stackTrace        the optional stacktrace
     */
    public ReTrace(String regularExpression, boolean verbose, Reader mapping, Reader stackTrace) {
        this(regularExpression, verbose, mapping, stackTrace, new OutputStreamWriter(System.out));
    }

    /**
     * Creates a new ReTrace object to process a stack trace from the given file, based on the given mapping file.
     *
     * @param regularExpression the regular expression for parsing the lines in the stack trace.
     * @param verbose           specifies whether the de-obfuscated stack trace should be verbose.
     * @param mapping           the mapping file that was written out by ProGuard.
     * @param stackTrace        the optional stacktrace
     * @param output            the output writer
     */
    public ReTrace(String regularExpression, boolean verbose, Reader mapping, Reader stackTrace, Writer output) {
        this.regularExpression = regularExpression;
        this.verbose = verbose;
        this.mapping = mapping;
        this.stackTrace = stackTrace;
        this.output = output;
    }


    /**
     * Performs the subsequent ReTrace operations.
     *
     * @throws IOException if something goes wrong during processing
     */
    public void execute() throws IOException {
        // Read the mapping file.
        MappingReader mappingReader = new MappingReader(mapping);
        mappingReader.pump(this);

        List<ExpressionType> expressionTypes = new ArrayList<>();
        Pattern pattern = createPatternFromRegularExpression(expressionTypes);

        // Open the stack trace file.
        LineNumberReader reader = new LineNumberReader(stackTrace == null ? new InputStreamReader(System.in) : new BufferedReader(stackTrace));

        PrintWriter output = new PrintWriter(this.output);

        // Read and process the lines of the stack trace.
        try {
            StringBuilder outLine = new StringBuilder(256);

            LineInfo lineInfo = new LineInfo();

            // Read all lines from the stack trace.
            while (true) {
                // Read a line.
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                // Try to match it against the regular expression.
                Matcher matcher = pattern.matcher(line);

                if (matcher.matches()) {
                    // The line matched the regular expression.
                    lineInfo.reset();

                    // Extract a class name, a line number, a type, and
                    // arguments.
                    for (int expressionTypeIndex = 0; expressionTypeIndex < expressionTypes.size(); expressionTypeIndex++) {
                        int startIndex = matcher.start(expressionTypeIndex + 1);
                        if (startIndex >= 0) {
                            String match = matcher.group(expressionTypeIndex + 1);
                            expressionTypes.get(expressionTypeIndex).setOriginalValue(this, match, lineInfo);
                        }
                    }

                    // Deconstruct the input line and reconstruct the output
                    // line. Also collect any additional output lines for this
                    // line.
                    int lineIndex = 0;

                    outLine.setLength(0);

                    for (int expressionTypeIndex = 0; expressionTypeIndex < expressionTypes.size(); expressionTypeIndex++) {
                        int startIndex = matcher.start(expressionTypeIndex + 1);
                        if (startIndex >= 0) {
                            int endIndex = matcher.end(expressionTypeIndex + 1);
                            String match = matcher.group(expressionTypeIndex + 1);

                            // Copy a literal piece of the input line.
                            outLine.append(line.substring(lineIndex, startIndex));

                            // Copy a matched and translated piece of the input line.
                            expressionTypes.get(expressionTypeIndex).setAndAppendOriginalValue(this, match, lineInfo, outLine);

                            // Skip the original element whose processed version
                            // has just been appended.
                            lineIndex = endIndex;
                        }
                    }

                    // Copy the last literal piece of the input line.
                    outLine.append(line.substring(lineIndex));

                    // Print out the processed line.
                    output.println(outLine);

                    // Print out any additional lines.
                    for (Object extraOutLine : lineInfo.getExtraOutLines()) {
                        output.println(extraOutLine);
                    }
                } else {
                    // The line didn't match the regular expression.
                    // Print out the original line.
                    output.println(line);
                }
            }
        } catch (IOException ex) {
            throw new IOException("Can't read stack trace (" + ex.getMessage() + ")");
        } finally {
            output.flush();
            if (stackTrace != null) {
                try {
                    reader.close();
                } catch (IOException ex) {
                    // This shouldn't happen.
                }
            }
        }
    }

    private static class LineInfo {
        private String className = null;
        private int lineNumber = 0;
        private String type = null;
        private String arguments = null;
        private final List<String> extraOutLines = new ArrayList<>();

        public void reset() {
            lineNumber = 0;
            type = null;
            arguments = null;
            extraOutLines.clear();
        }

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }

        public List<String> getExtraOutLines() {
            return extraOutLines;
        }
    }

    @FunctionalInterface
    private interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    @FunctionalInterface
    private interface QuadConsumer<T, U, V, W> {
        void accept(T t, U u, V v, W w);
    }

    @SuppressWarnings("unused")
    private enum ExpressionType {
        c(REGEX_CLASS, ReTrace::originalClassName, LineInfo::setClassName, StringBuilder::append),
        C(REGEX_CLASS_SLASH, (reTrace, match) -> reTrace.originalClassName(ClassUtil.externalClassName(match)), LineInfo::setClassName, StringBuilder::append),
        l(REGEX_LINE_NUMBER, (reTrace, match) -> Integer.parseInt(match), LineInfo::setLineNumber, StringBuilder::append),
        t(REGEX_TYPE, ReTrace::originalType, LineInfo::setType, StringBuilder::append),
        f(REGEX_MEMBER, null, ReTrace::originalFieldName),
        m(REGEX_MEMBER, null, ReTrace::originalMethodName),
        a(REGEX_ARGUMENTS, ReTrace::originalArguments, LineInfo::setArguments, StringBuilder::append);

        private final String regex;
        private final TriConsumer<ReTrace, String, LineInfo> setter;
        private final QuadConsumer<ReTrace, String, LineInfo, StringBuilder> setAndAppender;

        ExpressionType(String regex, TriConsumer<ReTrace, String, LineInfo> setter, QuadConsumer<ReTrace, String, LineInfo, StringBuilder> setAndAppender) {
            this.regex = regex;
            this.setter = setter;
            this.setAndAppender = setAndAppender;
        }

        <T> ExpressionType(String regex, BiFunction<ReTrace, String, T> transformer, BiConsumer<LineInfo, T> setter, BiConsumer<StringBuilder, T> appender) {
            this(regex, (reTrace, match, lineInfo) -> setter.accept(lineInfo, transformer.apply(reTrace, match)),
                    (reTrace, match, lineInfo, stringBuilder) -> setter.andThen((l, value) -> appender.accept(stringBuilder, value))
                            .accept(lineInfo, transformer.apply(reTrace, match)));
        }

        public String getRegex() {
            return regex;
        }

        public void setOriginalValue(ReTrace reTrace, String match, LineInfo lineInfo) {
            if (setter != null) {
                setter.accept(reTrace, match, lineInfo);
            }
        }

        public void setAndAppendOriginalValue(ReTrace reTrace, String match, LineInfo lineInfo, StringBuilder out) {
            if (setAndAppender != null) {
                setAndAppender.accept(reTrace, match, lineInfo, out);
            }
        }
    }

    private Pattern createPatternFromRegularExpression(List<ExpressionType> expressionTypes) {
        // Construct the regular expression.
        StringBuilder expressionBuffer = new StringBuilder(regularExpression.length() + 32);
        int index;
        int nextIndex;
        for (index = 0; (nextIndex = regularExpression.indexOf('%', index)) >= 0 && nextIndex < regularExpression.length(); index = nextIndex + 2) {
            expressionBuffer.append(regularExpression.substring(index, nextIndex));
            expressionBuffer.append('(');

            char expressionTypeChar = regularExpression.charAt(nextIndex + 1);
            try {
                ExpressionType expressionType = ExpressionType.valueOf(String.valueOf(expressionTypeChar));
                expressionBuffer.append(expressionType.getRegex());
                expressionTypes.add(expressionType);
            } catch (IllegalArgumentException ignored) {
            }

            expressionBuffer.append(')');
        }

        expressionBuffer.append(regularExpression.substring(index));

        return Pattern.compile(expressionBuffer.toString());
    }

    private void originalFieldName(String match, LineInfo lineInfo, StringBuilder outLine) {
        originalFieldName(lineInfo.getClassName(), match, lineInfo.getType(), outLine, lineInfo.getExtraOutLines());
    }

    private void originalMethodName(String match, LineInfo lineInfo, StringBuilder outLine) {
        originalMethodName(lineInfo.getClassName(), match, lineInfo.getLineNumber(), lineInfo.getType(), lineInfo.getArguments(), outLine, lineInfo.getExtraOutLines());
    }


    /**
     * Finds the original field name(s), appending the first one to the out line, and any additional alternatives to the extra lines.
     */
    private void originalFieldName(String className, String obfuscatedFieldName, String type, StringBuilder outLine, List<String> extraOutLines) {
        int extraIndent = -1;

        // Class name -> obfuscated field names.
        Map<String, Set<FieldInfo>> fieldMap = classFieldMap.get(className);
        if (fieldMap != null) {
            // Obfuscated field names -> fields.
            Set<FieldInfo> fieldSet = fieldMap.get(obfuscatedFieldName);
            if (fieldSet != null) {
                // Find all matching fields.
                for (FieldInfo fieldInfo : fieldSet) {
                    if (fieldInfo.matches(type)) {
                        // Is this the first matching field?
                        if (extraIndent < 0) {
                            extraIndent = outLine.length();

                            // Append the first original name.
                            if (verbose) {
                                outLine.append(fieldInfo.type).append(' ');
                            }
                            outLine.append(fieldInfo.originalName);
                        } else {
                            // Create an additional line with the proper
                            // indentation.
                            StringBuilder extraBuffer = new StringBuilder();
                            for (int counter = 0; counter < extraIndent; counter++) {
                                extraBuffer.append(' ');
                            }

                            // Append the alternative name.
                            if (verbose) {
                                extraBuffer.append(fieldInfo.type).append(' ');
                            }
                            extraBuffer.append(fieldInfo.originalName);

                            // Store the additional line.
                            extraOutLines.add(extraBuffer.toString());
                        }
                    }
                }
            }
        }

        // Just append the obfuscated name if we haven't found any matching
        // fields.
        if (extraIndent < 0) {
            outLine.append(obfuscatedFieldName);
        }
    }


    /**
     * Finds the original method name(s), appending the first one to the out line, and any additional alternatives to the extra lines.
     */
    private void originalMethodName(String className, String obfuscatedMethodName, int lineNumber, String type, String arguments, StringBuilder outLine,
            List<String> extraOutLines) {
        int extraIndent = -1;

        // Class name -> obfuscated method names.
        Map<String, Set<MethodInfo>> methodMap = classMethodMap.get(className);
        if (methodMap != null) {
            // Obfuscated method names -> methods.
            Set<MethodInfo> methodSet = methodMap.get(obfuscatedMethodName);
            if (methodSet != null) {
                // Find all matching methods.
                for (MethodInfo methodInfo : methodSet) {
                    if (methodInfo.matches(lineNumber, type, arguments)) {
                        // Is this the first matching method?
                        if (extraIndent < 0) {
                            extraIndent = outLine.length();

                            // Append the first original name.
                            if (verbose) {
                                outLine.append(methodInfo.type).append(' ');
                            }
                            outLine.append(methodInfo.originalName);
                            if (verbose) {
                                outLine.append('(').append(methodInfo.arguments).append(')');
                            }
                        } else {
                            // Create an additional line with the proper
                            // indentation.
                            StringBuilder extraBuffer = new StringBuilder();
                            for (int counter = 0; counter < extraIndent; counter++) {
                                extraBuffer.append(' ');
                            }

                            // Append the alternative name.
                            if (verbose) {
                                extraBuffer.append(methodInfo.type).append(' ');
                            }
                            extraBuffer.append(methodInfo.originalName);
                            if (verbose) {
                                extraBuffer.append('(').append(methodInfo.arguments).append(')');
                            }

                            // Store the additional line.
                            extraOutLines.add(extraBuffer.toString());
                        }
                    }
                }
            }
        }

        // Just append the obfuscated name if we haven't found any matching
        // methods.
        if (extraIndent < 0) {
            outLine.append(obfuscatedMethodName);
        }
    }


    /**
     * Returns the original argument types.
     */
    private String originalArguments(String obfuscatedArguments) {
        StringBuilder originalArguments = new StringBuilder();

        int startIndex = 0;
        while (true) {
            int endIndex = obfuscatedArguments.indexOf(',', startIndex);
            if (endIndex < 0) {
                break;
            }

            originalArguments.append(originalType(obfuscatedArguments.substring(startIndex, endIndex).trim())).append(',');

            startIndex = endIndex + 1;
        }

        originalArguments.append(originalType(obfuscatedArguments.substring(startIndex).trim()));

        return originalArguments.toString();
    }


    /**
     * Returns the original type.
     */
    private String originalType(String obfuscatedType) {
        int index = obfuscatedType.indexOf('[');

        return index >= 0 ? originalClassName(obfuscatedType.substring(0, index)) + obfuscatedType.substring(index) : originalClassName(obfuscatedType);
    }


    /**
     * Returns the original class name.
     */
    private String originalClassName(String obfuscatedClassName) {
        String originalClassName = classMap.get(obfuscatedClassName);

        return originalClassName != null ? originalClassName : obfuscatedClassName;
    }


    // Implementations for MappingProcessor.

    public void processClassMapping(String className, String newClassName) {
        // Obfuscated class name -> original class name.
        classMap.put(newClassName, className);

    }


    public void processFieldMapping(String className, String fieldType, String fieldName, String newFieldName) {
        // Original class name -> obfuscated field names.
        Map<String, Set<FieldInfo>> fieldMap = classFieldMap.computeIfAbsent(className, k -> new HashMap<>());

        // Obfuscated field name -> fields.
        Set<FieldInfo> fieldSet = fieldMap.computeIfAbsent(newFieldName, k -> new LinkedHashSet<>());

        // Add the field information.
        fieldSet.add(new FieldInfo(fieldType, fieldName));
    }


    public void processMethodMapping(String className, int firstLineNumber, int lastLineNumber, String methodReturnType, String methodName, String methodArguments,
            String newMethodName) {
        // Original class name -> obfuscated method names.
        Map<String, Set<MethodInfo>> methodMap = classMethodMap.computeIfAbsent(className, k -> new HashMap<>());

        // Obfuscated method name -> methods.
        Set<MethodInfo> methodSet = methodMap.computeIfAbsent(newMethodName, k -> new LinkedHashSet<>());

        // Add the method information.
        methodSet.add(new MethodInfo(firstLineNumber, lastLineNumber, methodReturnType, methodArguments, methodName));
    }


    /**
     * A field record.
     */
    private static class FieldInfo {
        private final String type;
        private final String originalName;


        private FieldInfo(String type, String originalName) {
            this.type = type;
            this.originalName = originalName;
        }


        private boolean matches(String type) {
            return type == null || type.equals(this.type);
        }
    }


    /**
     * A method record.
     */
    private static class MethodInfo {
        private final int firstLineNumber;
        private final int lastLineNumber;
        private final String type;
        private final String arguments;
        private final String originalName;


        private MethodInfo(int firstLineNumber, int lastLineNumber, String type, String arguments, String originalName) {
            this.firstLineNumber = firstLineNumber;
            this.lastLineNumber = lastLineNumber;
            this.type = type;
            this.arguments = arguments;
            this.originalName = originalName;
        }


        private boolean matches(int lineNumber, String type, String arguments) {
            return (lineNumber == 0 || (firstLineNumber <= lineNumber && lineNumber <= lastLineNumber) || lastLineNumber == 0) && (type == null || type.equals(this.type)) &&
                    (arguments == null || arguments.equals(this.arguments));
        }
    }


    /**
     * The main program for ReTrace.
     *
     * @param args commandline arguments
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java proguard.ReTrace [-verbose] <mapping_file> [<stacktrace_file>]");
            System.exit(-1);
        }

        String regularExpression = STACK_TRACE_EXPRESSION;
        boolean verbose = false;

        int argumentIndex = 0;
        label:
        while (argumentIndex < args.length) {
            String arg = args[argumentIndex];
            switch (arg) {
                case REGEX_OPTION:
                    regularExpression = args[++argumentIndex];
                    break;
                case VERBOSE_OPTION:
                    verbose = true;
                    break;
                default:
                    break label;
            }

            argumentIndex++;
        }

        if (argumentIndex >= args.length) {
            System.err.println("Usage: java proguard.ReTrace [-regex <regex>] [-verbose] <mapping_file> [<stacktrace_file>]");
            System.exit(-1);
        }

        File mappingFile = new File(args[argumentIndex++]);
        File stackTraceFile = argumentIndex < args.length ? new File(args[argumentIndex]) : null;

        try {
            ReTrace reTrace = new ReTrace(regularExpression, verbose, new FileReader(mappingFile), stackTraceFile == null ? null : new FileReader(stackTraceFile));
            // Execute ReTrace with its given settings.
            reTrace.execute();
        } catch (IOException ex) {
            if (verbose) {
                // Print a verbose stack trace.
                ex.printStackTrace();
            } else {
                // Print just the stack trace message.
                System.err.println("Error: " + ex.getMessage());
            }

            System.exit(1);
        }

        System.exit(0);
    }
}
