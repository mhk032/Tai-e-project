/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.graph.callgraph.CallGraphBuilder;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IRPrinter;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.Strings;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static pascal.taie.util.collection.CollectionUtils.getOne;

/**
 * Special class for process the results of other analyses after they finish.
 * This class is designed mainly for testing purpose. Currently, it supports
 * input/output analysis results from/to file, and compare analysis results
 * with input results. This analysis should be placed after the other analyses.
 */
public class ResultProcessor extends ProgramAnalysis {

    public static final String ID = "process-result";

    private static final Logger logger = LogManager.getLogger(ResultProcessor.class);

    private final String action;

    private PrintStream out;

    private MultiMap<Pair<String, String>, String> inputs;

    private Set<String> mismatches;

    public ResultProcessor(AnalysisConfig config) {
        super(config);
        action = getOptions().getString("action");
    }

    @Override
    public Object analyze() {
        // initialization
        switch (action) {
            case "dump" -> setOutput();
            case "compare" -> readInputs();
        }
        mismatches = new LinkedHashSet<>();
        // Classify given analysis IDs into two groups, one for inter-procedural
        // and the another one for intra-procedural analysis.
        // If an ID has result in World, then it is classified as
        // inter-procedural analysis, and others are intra-procedural analyses.
        @SuppressWarnings("unchecked")
        Map<Boolean, List<String>> groups = ((List<String>) getOptions().get("analyses"))
                .stream()
                .collect(Collectors.groupingBy(id -> World.getResult(id) != null));
        if (groups.containsKey(true)) {
            processProgramAnalysisResult(groups.get(true));
        }
        if (groups.containsKey(false)) {
            processMethodAnalysisResult(groups.get(false));
        }
        if (getOptions().getBoolean("log-mismatches")) {
            mismatches.forEach(logger::info);
        }
        // close out stream
        if (action.equals("dump") && out != System.out) {
            out.close();
        }
        return mismatches;
    }

    private void setOutput() {
        String output = getOptions().getString("file");
        if (output != null) {
            try {
                out = new PrintStream(output);
            } catch (FileNotFoundException e) {
                throw new RuntimeException("Failed to open output file", e);
            }
        } else {
            out = System.out;
        }
    }

    private void readInputs() {
        String input = getOptions().getString("file");
        Path path = Path.of(input);
        try {
            inputs = Maps.newMultiMap();
            BufferedReader reader = Files.newBufferedReader(path);
            String line;
            Pair<String, String> currentKey = null;
            while ((line = reader.readLine()) != null) {
                Pair<String, String> key = extractKey(line);
                if (key != null) {
                    currentKey = key;
                } else if (!line.isBlank()) {
                    inputs.put(currentKey, line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read input file", e);
        }
    }

    private static Pair<String, String> extractKey(String line) {
        if (line.startsWith("----------") && line.endsWith("----------")) {
            int ms = line.indexOf('<'); // method start
            int me = line.indexOf("> "); // method end
            String method = line.substring(ms, me + 1);
            int as = line.lastIndexOf('('); // analysis start
            int ae = line.lastIndexOf(')'); // analysis end
            String analysis = line.substring(as + 1, ae);
            return new Pair<>(method, analysis);
        } else {
            return null;
        }
    }

    private void processProgramAnalysisResult(List<String> analyses) {
        Comparator<JMethod> comp = (m1, m2) -> {
            if (m1.getDeclaringClass().equals(m2.getDeclaringClass())) {
                return m1.getIR().getStmt(0).getLineNumber() -
                        m2.getIR().getStmt(0).getLineNumber();
            } else {
                return m1.getDeclaringClass().toString()
                        .compareTo(m2.getDeclaringClass().toString());
            }
        };
        CallGraph<?, JMethod> cg = World.getResult(CallGraphBuilder.ID);
        List<JMethod> methods = cg.reachableMethods()
                .filter(m -> m.getDeclaringClass().isApplication())
                .sorted(comp)
                .toList();
        processResults(methods, analyses, (m, id) -> World.getResult(id));
    }

    private void processMethodAnalysisResult(List<String> analyses) {
        List<JMethod> methods = World.getClassHierarchy()
                .applicationClasses()
                .map(JClass::getDeclaredMethods)
                .flatMap(Collection::stream)
                .filter(m -> !m.isAbstract() && !m.isNative())
                .sorted(Comparator.comparing(m ->
                        m.getIR().getStmt(0).getLineNumber()))
                .toList();
        processResults(methods, analyses, (m, id) -> m.getIR().getResult(id));
    }

    private void processResults(List<JMethod> methods, List<String> analyses,
                                BiFunction<JMethod, String, ?> resultGetter) {
        Set<Pair<String, String>> processed = Sets.newSet();
        methods.forEach(method ->
                analyses.forEach(id -> {
                    switch (action) {
                        case "dump" -> dumpResult(method, id, resultGetter);
                        case "compare" -> compareResult(method, id, resultGetter);
                    }
                    processed.add(new Pair<>(method.toString(), id));
                })
        );
        // check whether expected analysis results of some methods are absent
        // in given results.
        for (var key : inputs.keySet()) {
            if (!processed.contains(key)) {
                mismatches.add(String.format("Expected \"%s\" result of %s" +
                                " is absent in given results",
                        key.second(), key.first()));
            }
        }
    }

    private void dumpResult(JMethod method, String id,
                            BiFunction<JMethod, String, ?> resultGetter) {
        out.printf("-------------------- %s (%s) --------------------%n", method, id);
        Object result = resultGetter.apply(method, id);
        if (result instanceof Set) {
            ((Set<?>) result).forEach(e -> out.println(toString(e)));
        } else if (result instanceof StmtResult<?> stmtResult) {
            method.getIR()
                    .stmts()
                    .filter(stmtResult::isRelevant)
                    .forEach(stmt -> out.println(toString(stmt, stmtResult)));
        } else {
            out.println(toString(result));
        }
        out.println();
    }

    /**
     * Converts an object to string representation.
     * Here we specially handle Stmt by calling IRPrint.toString().
     */
    private static String toString(Object o) {
        if (o instanceof Stmt) {
            return IRPrinter.toString((Stmt) o);
        } else if (o instanceof Collection) {
            return Strings.toString((Collection<?>) o);
        } else {
            return Objects.toString(o);
        }
    }

    /**
     * Converts a stmt and its analysis result to the corresponding
     * string representation.
     */
    private static String toString(Stmt stmt, StmtResult<?> result) {
        return toString(stmt) + " " + toString(result.getResult(stmt));
    }

    private void compareResult(JMethod method, String id,
                               BiFunction<JMethod, String, ?> resultGetter) {
        Set<String> inputResult = inputs.get(new Pair<>(method.toString(), id));
        Object result = resultGetter.apply(method, id);
        if (result instanceof Set) {
            Set<String> given = ((Set<?>) result)
                    .stream()
                    .map(ResultProcessor::toString)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            given.forEach(s -> {
                if (!inputResult.contains(s)) {
                    mismatches.add(method + " " + s +
                            " should NOT be included");
                }
            });
            inputResult.forEach(s -> {
                if (!given.contains(s)) {
                    mismatches.add(method + " " + s +
                            " should be included");
                }
            });
        } else if (result instanceof StmtResult<?> stmtResult) {
            Set<String> lines = inputs.get(new Pair<>(method.toString(), id));
            method.getIR()
                    .stmts()
                    .filter(stmtResult::isRelevant)
                    .forEach(stmt -> {
                        String stmtStr = toString(stmt);
                        String given = toString(stmt, stmtResult);
                        boolean foundExpeceted = false;
                        for (String line : lines) {
                            if (line.startsWith(stmtStr)) {
                                foundExpeceted = true;
                                if (!line.equals(given)) {
                                    int idx = stmtStr.length();
                                    mismatches.add(String.format("%s %s expected: %s, given: %s",
                                            method, stmtStr, line.substring(idx + 1),
                                            given.substring(idx + 1)));
                                }
                            }
                        }
                        if (!foundExpeceted) {
                            int idx = stmtStr.length();
                            mismatches.add(String.format("%s %s expected: null, given: %s",
                                    method, stmtStr, given.substring(idx + 1)));
                        }
                    });
        } else if (inputResult.size() == 1) {
            if (!toString(result).equals(getOne(inputResult))) {
                mismatches.add(String.format("%s expected: %s, given: %s",
                        method, getOne(inputResult), toString(result)));
            }
        } else {
            logger.warn("Cannot compare result of analysis {} for {}," +
                            " expected: {}, given: {}",
                    id, method, inputResult, result);
        }
    }
}
