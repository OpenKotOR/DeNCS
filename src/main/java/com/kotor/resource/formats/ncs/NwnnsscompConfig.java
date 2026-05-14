// Copyright 2021-2025 DeNCS
// Licensed under the MIT License. See LICENSE in the project root for full license text.

package com.kotor.resource.formats.ncs;

import java.io.File;
import java.io.IOException;

/**
 * Resolves and formats command-line arguments for nwnnsscomp across versions.
 * <p>
 * Different nwnnsscomp releases are not backwards compatible, so we detect the
 * executable by SHA256 ({@link HashUtil}) and then hydrate the correct
 * argument template from {@link KnownExternalCompilers}. Consumers only need
 * to supply the executable path plus the source/target files and game flavor.
 */
public class NwnnsscompConfig {
   /** Detected SHA256 fingerprint of the provided compiler binary. */
   private final String sha256Hash;
   /** Source script being compiled or decompiled. */
   private final File sourceFile;
   /** Destination file path to be produced by the compiler. */
   private final File outputFile;
   /** Parent directory of the output file, used by some argument templates. */
   private final File outputDir;
   /** Basename of the output file, used when templates require only a name. */
   private final String outputName;
   /** True when targeting KotOR 2 (TSL) arguments, false for KotOR 1. */
   private final boolean isK2;
   /** Selected compiler metadata derived from the SHA256 fingerprint. */
   private final KnownExternalCompilers chosenCompiler;

   /**
    * Creates a new configuration for nwnnsscomp execution.
    *
    * @param compilerPath Path to the nwnnsscomp.exe file
    * @param sourceFile The source file to compile/decompile
    * @param outputFile The output file path
    * @param isK2 true for KotOR 2 (TSL), false for KotOR 1
    * @throws IOException If the compiler file cannot be read or hashed
    * @throws IllegalArgumentException If the compiler version is not recognized
    */
   public NwnnsscompConfig(File compilerPath, File sourceFile, File outputFile, boolean isK2)
         throws IOException {
      this.sourceFile = sourceFile;
      // Convert to absolute path to ensure parent directory is always available
      File absoluteOutputFile = outputFile.getAbsoluteFile();
      this.outputFile = absoluteOutputFile;
      this.outputDir = absoluteOutputFile.getParentFile();
      this.outputName = absoluteOutputFile.getName();
      this.isK2 = isK2;

      // Calculate hash of the compiler executable
      this.sha256Hash = HashUtil.calculateSHA256(compilerPath);

      // Look up the compiler version
      this.chosenCompiler = KnownExternalCompilers.fromSha256(this.sha256Hash);
      if (this.chosenCompiler == null) {
         throw new IllegalArgumentException(
            "Unknown compiler version with SHA256 hash: " + this.sha256Hash +
            ". This compiler may not be supported. Please use a known version of nwnnsscomp.exe.");
      }
   }

   /**
    * Gets the formatted compile command-line arguments.
    *
    * @param executable Path to the nwnnsscomp executable
    * @return Array of command-line arguments
    */
   public String[] getCompileArgs(String executable) {
      return formatArgs(chosenCompiler.getCompileArgs(), executable);
   }

   /**
    * Gets the formatted compile command-line arguments and appends include paths.
    *
    * @param executable Path to the nwnnsscomp executable
    * @param includeDirs Optional include directories to append via {@code -i}
    * @return Array of command-line arguments
    */
   public String[] getCompileArgs(String executable, java.util.List<File> includeDirs) {
      // Build include arguments array for {includes} placeholder
      // Note: KOTOR Tool and KOTOR Scripting Tool don't support -i flag - they expect includes in same directory as source
      java.util.List<String> includeArgs = new java.util.ArrayList<>();
      if (includeDirs != null && !includeDirs.isEmpty()) {
         // Only add -i flags if compiler supports them (not KOTOR Tool or KOTOR Scripting Tool)
         boolean supportsIncludeFlag = (chosenCompiler != KnownExternalCompilers.KOTOR_TOOL
               && chosenCompiler != KnownExternalCompilers.KOTOR_SCRIPTING_TOOL);
         if (supportsIncludeFlag) {
            for (File dir : includeDirs) {
               if (dir != null && dir.exists()) {
                  includeArgs.add("-i");
                  includeArgs.add(dir.getAbsolutePath());
               }
            }
         }
         // For KOTOR Tool and KOTOR Scripting Tool, include files must be in the same directory as the source file
         // The caller should copy include files to the source directory if needed
      }

      // Get base template args
      String[] template = chosenCompiler.getCompileArgs();
      java.util.List<String> args = new java.util.ArrayList<>();

      // Process template and expand {includes} placeholder
      for (String arg : template) {
         if (arg.equals("{includes}")) {
            // Insert include arguments at this position
            args.addAll(includeArgs);
         } else {
            // Format the argument (replacing other placeholders)
            String formatted = arg
               .replace("{source}", sourceFile.getAbsolutePath())
               .replace("{output}", outputFile.getAbsolutePath())
               .replace("{output_dir}", outputDir != null ? outputDir.getAbsolutePath() : "")
               .replace("{output_name}", outputName)
               .replace("{game_value}", isK2 ? "2" : "1");
            args.add(formatted);
         }
      }

      return buildCommand(executable, args);
   }

   /**
    * Gets the formatted decompile command-line arguments.
    *
    * @param executable Path to the nwnnsscomp executable (or ncsdis.exe)
    * @return Array of command-line arguments
    * @throws UnsupportedOperationException If decompilation is not supported
    */
   public String[] getDecompileArgs(String executable) {
      if (!chosenCompiler.supportsDecompilation()) {
         throw new UnsupportedOperationException(
            "Compiler '" + chosenCompiler.getName() + "' does not support decompilation");
      }

      // Special handling for ncsdis.exe which uses a simpler command line: ncsdis.exe <input.ncs> <output.pcode>
      // No -d, -o, or -g flags
      if (chosenCompiler == KnownExternalCompilers.NCSDIS) {
         // ncsdis command: ncsdis.exe <input.ncs> <output.pcode>
         java.util.List<String> args = new java.util.ArrayList<>();
         args.add(sourceFile.getAbsolutePath());
         args.add(outputFile.getAbsolutePath());
         return buildCommand(executable, args);
      }

      return formatArgs(chosenCompiler.getDecompileArgs(), executable);
   }

   /**
    * Formats the argument template with actual values.
    *
    * @param argsList The argument template array
    * @param executable The executable path
   * @return Formatted argument array where placeholders ({@code {source}}, {@code {output}},
   * {@code {output_dir}}, {@code {output_name}}, {@code {game_value}}, {@code {includes}}) are replaced
    */
   private String[] formatArgs(String[] argsList, String executable) {
      java.util.List<String> formatted = new java.util.ArrayList<>();
      for (String arg : argsList) {
         String replaced = arg
            .replace("{source}", sourceFile.getAbsolutePath())
            .replace("{output}", outputFile.getAbsolutePath())
            .replace("{output_dir}", outputDir != null ? outputDir.getAbsolutePath() : "")
            .replace("{output_name}", outputName)
            .replace("{game_value}", isK2 ? "2" : "1")
            .replace("{includes}", ""); // Remove {includes} placeholder when no includes provided
         // Only add non-empty arguments
         if (!replaced.isEmpty()) {
            formatted.add(replaced);
         }
      }

      return buildCommand(executable, formatted);
   }

   /**
    * Builds a command array from executable and arguments, prepending {@code wine} on
    * non-Windows systems when the executable is a {@code .exe} file.
    *
    * @param executable The executable path
    * @param args The list of arguments (excluding the executable itself)
    * @return Full command array ready for {@link ProcessBuilder}
    */
   private String[] buildCommand(String executable, java.util.List<String> args) {
      String osName = System.getProperty("os.name").toLowerCase();
      boolean isWindows = osName.startsWith("windows");

      if (!isWindows && executable.toLowerCase().endsWith(".exe")) {
         // Use Wine to run .exe on non-Windows platforms
         String[] result = new String[args.size() + 2];
         result[0] = "wine";
         result[1] = executable;
         for (int i = 0; i < args.size(); i++) {
            result[i + 2] = args.get(i);
         }
         return result;
      } else {
         // Prepend the executable path (Windows or non-.exe executable)
         String[] result = new String[args.size() + 1];
         result[0] = executable;
         for (int i = 0; i < args.size(); i++) {
            result[i + 1] = args.get(i);
         }
         return result;
      }
   }

   /**
    * Gets the detected compiler information.
    *
    * @return The detected compiler enum value
    */
   public KnownExternalCompilers getChosenCompiler() {
      return chosenCompiler;
   }

   /**
    * Gets the SHA256 hash of the compiler executable.
    *
    * @return The SHA256 hash
    */
   public String getSha256Hash() {
      return sha256Hash;
   }
}


