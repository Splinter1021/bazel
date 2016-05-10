// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.buildjar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.google.devtools.build.buildjar.javac.BlazeJavacMain;
import com.google.devtools.build.buildjar.javac.JavacRunner;
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin;

import com.sun.tools.javac.main.Main.Result;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.zip.ZipEntry;

/**
 * A command line interface to compile a java_library rule using in-process
 * javac. This allows us to spawn multiple java_library compilations on a
 * single machine or distribute Java compilations to multiple machines.
 */
public abstract class AbstractJavaBuilder extends AbstractLibraryBuilder {

  /** The name of the protobuf meta file. */
  private static final String PROTOBUF_META_NAME = "protobuf.meta";

  /** Enables more verbose output from the compiler. */
  protected boolean debug = false;

  /**
   * Prepares a compilation run and sets everything up so that the source files in the build request
   * can be compiled. Invokes compileSources to do the actual compilation.
   *
   * @param build A JavaLibraryBuildRequest request object describing what to compile
   * @param err PrintWriter for logging any diagnostic output
   */
  public Result compileJavaLibrary(final JavaLibraryBuildRequest build, final PrintWriter err)
      throws Exception {
    prepareSourceCompilation(build);
    if (build.getSourceFiles().isEmpty()) {
      return Result.OK;
    }
    JavacRunner javacRunner =
        new JavacRunner() {
          @Override
          public Result invokeJavac(
              ImmutableList<BlazeJavaCompilerPlugin> plugins, String[] args, PrintWriter output) {
            return new BlazeJavacMain(output, plugins).compile(args);
          }
        };
    Result result = compileSources(build, javacRunner, err);
    runClassPostProcessing(build);
    return result;
  }

  /**
   * Build a jar file containing source files that were generated by an annotation processor.
   */
  public abstract void buildGensrcJar(JavaLibraryBuildRequest build) throws IOException;

  @VisibleForTesting
  protected void runClassPostProcessing(JavaLibraryBuildRequest build)
      throws IOException {
    for (AbstractPostProcessor postProcessor : build.getPostProcessors()) {
      postProcessor.initialize(build);
      postProcessor.processRequest();
    }
  }

  /**
   * Compiles the java files specified in 'JavaLibraryBuildRequest'.
   * Implementations can try to avoid recompiling the java files. Whenever
   * this function is invoked, it is guaranteed that the build request
   * contains files to compile.
   *
   * @param build A JavaLibraryBuildRequest request object describing what to
   *              compile
   * @param err PrintWriter for logging any diagnostic output
   * @return the exit status of the java compiler.
   */
  abstract Result compileSources(JavaLibraryBuildRequest build, JavacRunner javacRunner,
      PrintWriter err) throws IOException;

  /**
   * Perform the build.
   */
  public Result run(JavaLibraryBuildRequest build, PrintWriter err) throws Exception {
    Result result = Result.ERROR;
    try {
      result = compileJavaLibrary(build, err);
      if (result.isOK()) {
        buildJar(build);
        if (!build.getProcessors().isEmpty()) {
          if (build.getGeneratedSourcesOutputJar() != null) {
            buildGensrcJar(build);
          }
        }
      }
    } finally {
      build.getDependencyModule().emitDependencyInformation(build.getClassPath(), result.isOK());
      build.getProcessingModule().emitManifestProto();
    }
    return result;
  }

  /**
   * A SourceJarEntryListener that collects protobuf meta data files from the
   * source jar files.
   */
  private static class ProtoMetaFileCollector implements SourceJarEntryListener {

    private final String sourceDir;
    private final String outputDir;
    private final ByteArrayOutputStream buffer;

    public ProtoMetaFileCollector(String sourceDir, String outputDir) {
      this.sourceDir = sourceDir;
      this.outputDir = outputDir;
      this.buffer = new ByteArrayOutputStream();
    }

    @Override
    public void onEntry(ZipEntry entry) throws IOException {
      String entryName = entry.getName();
      if (!entryName.equals(PROTOBUF_META_NAME)) {
        return;
      }
      Files.copy(new File(sourceDir, PROTOBUF_META_NAME), buffer);
    }

    /**
     * Writes the combined the meta files into the output directory. Delete the
     * stalling meta file if no meta file is collected.
     */
    @Override
    public void finish() throws IOException {
      File outputFile = new File(outputDir, PROTOBUF_META_NAME);
      if (buffer.size() > 0) {
        try (OutputStream outputStream = new FileOutputStream(outputFile)) {
          buffer.writeTo(outputStream);
        }
      } else if (outputFile.exists()) {
        // Delete stalled meta file.
        outputFile.delete();
      }
    }
  }

  @Override
  protected List<SourceJarEntryListener> getSourceJarEntryListeners(
      JavaLibraryBuildRequest build) {
    List<SourceJarEntryListener> result = super.getSourceJarEntryListeners(build);
    result.add(new ProtoMetaFileCollector(
        build.getTempDir(), build.getClassDir()));
    return result;
  }
}