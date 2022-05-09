package com.github.bazel_contrib.contrib_rules_jvm.javaparser.generators;

import com.github.bazel_contrib.contrib_rules_jvm.javaparser.file.BuildFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.utils.Pair;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PackageParser {
  private static final Logger logger = LoggerFactory.getLogger(PackageParser.class);

  private final Path workspace;
  private final List<PackageDependencies> packages = new ArrayList<>();
  private static final String BUILD_FILE = "BUILD.bazel";
  private JavaParser javaParser;

  public PackageParser(Path workspace) {
    this.workspace = workspace;
  }

  public void setup(String srcs, String tests, String generated) throws IOException {
    KnownTypeSolvers solvers = new KnownTypeSolvers(packages);

    getPackages(srcs, solvers, packages);
    getPackages(tests, solvers, packages);

    solvers.getInternalSolvers(workspace, srcs);
    solvers.getInternalSolvers(workspace, tests);
    if (generated != null) {
      solvers.getInternalSolvers(workspace, generated);
    }

    // configure java parser
    ParserConfiguration config =
        new ParserConfiguration().setSymbolResolver(solvers.getTypeSolver());
    javaParser = new JavaParser(config);
    for (PackageDependencies dependency : packages) {
      dependency.setJavaParser(javaParser);
    }
  }

  public JavaParser getJavaParser() {
    return javaParser;
  }

  // This was a temporary idea to output the imported / dependencies as a json blob on the command
  // line for reading
  // This continues to be here for legacy reasons but will be removed in the future.
  @Deprecated
  public void runImports(String imports) throws IOException {
    ClasspathParser parser = new ClasspathParser(this.javaParser);
    parser.parseClasses(imports, workspace);
    List<String> types = new ArrayList<>(parser.getUsedTypes());
    Gson gson = new Gson();
    String encoded = gson.toJson(types);
    // Output as a direct data on the command line rather than via a logger to not include the
    // logger headers.
    System.out.println(encoded);
  }

  public ClasspathParser getImports(Path directory, List<String> files) throws IOException {
    ClasspathParser parser = new ClasspathParser(this.javaParser);
    parser.parseClasses(workspace, directory, files);
    return parser;
  }

  public void runAll(Boolean dryRun) throws IOException {
    // Resolve the types for all of the packages
    resolvePackages(packages);
    // Output the results
    logger.info("Number of packages: {}", packages.size());
    for (PackageDependencies pkg : packages) {
      logger.debug(pkg.getBuildFile().getBazelTarget() + " -> " + pkg);
      logger.info("\n{}", pkg.updateBuildFile(dryRun));
    }
  }

  private void resolvePackages(List<PackageDependencies> packages) throws IOException {
    try {
      packages.stream()
          .map(pkg -> new Pair<>(pkg.getPackagePath().getParent(), new BuildFileVisitor(pkg)))
          .forEach(
              both -> {
                try {
                  Files.walkFileTree(both.a, both.b);
                } catch (IOException ex) {
                  logger.error("Failed to walk tree for: {} \n exception: {} ", both.a, ex);
                  throw new RuntimeException(ex);
                }
              });
    } catch (RuntimeException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      } else {
        logger.error("Got unexpected exception from walking the file tree", cause);
        throw e;
      }
    }
  }

  /**
   * Find the Bazel build files and collect them as the bazel packages. The corresponding
   * directory(ies) will be scanned for source files.
   *
   * @param srcs - Name (directory regex) of the directories to scan for build files
   * @param solvers - The type resolution collection
   * @param packages - The list of packages, this will be updated as a result of the scan process.
   * @throws IOException when reading path information fails.
   */
  private void getPackages(
      String srcs, KnownTypeSolvers solvers, List<PackageDependencies> packages)
      throws IOException {
    String pattern = workspace.toString() + "/" + srcs + "/**/" + BUILD_FILE;
    logger.debug("Pattern for processing: {}", pattern);
    PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    try (Stream<Path> paths =
        Files.find(workspace, Integer.MAX_VALUE, (path, f) -> pathMatcher.matches(path))) {
      packages.addAll(
          paths
              .map(
                  path ->
                      new PackageDependencies(
                          new BuildFile(
                              path,
                              "\"//" + workspace.relativize(path).getParent().toString() + "\",",
                              BuildFile.parse(path)),
                          solvers))
              .collect(Collectors.toList()));
    }
  }
}
