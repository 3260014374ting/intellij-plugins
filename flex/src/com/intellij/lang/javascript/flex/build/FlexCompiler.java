package com.intellij.lang.javascript.flex.build;

import com.intellij.compiler.options.CompileStepBeforeRun;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.lang.javascript.flex.FlexUtils;
import com.intellij.lang.javascript.flex.actions.airpackage.AirPackageProjectParameters;
import com.intellij.lang.javascript.flex.flexunit.FlexUnitRunConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.model.*;
import com.intellij.lang.javascript.flex.projectStructure.model.impl.Factory;
import com.intellij.lang.javascript.flex.projectStructure.options.BCUtils;
import com.intellij.lang.javascript.flex.projectStructure.options.BuildConfigurationNature;
import com.intellij.lang.javascript.flex.projectStructure.ui.*;
import com.intellij.lang.javascript.flex.run.BCBasedRunnerParameters;
import com.intellij.lang.javascript.flex.run.FlashRunConfiguration;
import com.intellij.lang.javascript.flex.run.FlashRunnerParameters;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.IOException;
import java.util.*;

import static com.intellij.lang.javascript.flex.projectStructure.model.AirPackagingOptions.FilePathAndPathInPackage;
import static com.intellij.lang.javascript.flex.projectStructure.model.CompilerOptions.ResourceFilesMode;
import static com.intellij.lang.javascript.flex.run.FlashRunnerParameters.AirMobileRunTarget;

public class FlexCompiler implements SourceProcessingCompiler {
  private static final Logger LOG = Logger.getInstance(FlexCompiler.class.getName());
  public static final Key<Collection<Pair<Module, FlexIdeBuildConfiguration>>> MODULES_AND_BCS_TO_COMPILE =
    Key.create("modules.and.bcs.to.compile");

  @NotNull
  public ProcessingItem[] getProcessingItems(final CompileContext context) {
    saveProject(context.getProject());
    final List<ProcessingItem> itemList = new ArrayList<ProcessingItem>();

    try {
      for (final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : getModulesAndBCsToCompile(context.getCompileScope())) {
        itemList.add(new MyProcessingItem(moduleAndBC.first, moduleAndBC.second));
      }
    }
    catch (ConfigurationException e) {
      // can't happen because already validated
      throw new RuntimeException(e);
    }

    return itemList.toArray(new ProcessingItem[itemList.size()]);
  }

  private static void saveProject(final Project project) {
    Runnable runnable = new Runnable() {
      public void run() {
        project.save();
      }
    };

    if (ApplicationManager.getApplication().isDispatchThread()) {
      runnable.run();
    }
    else {
      ApplicationManager.getApplication().invokeAndWait(runnable, ModalityState.defaultModalityState());
    }
  }

  public ProcessingItem[] process(final CompileContext context, final ProcessingItem[] items) {
    final FlexCompilerHandler flexCompilerHandler = FlexCompilerHandler.getInstance(context.getProject());
    final FlexCompilerProjectConfiguration flexCompilerConfiguration = FlexCompilerProjectConfiguration.getInstance(context.getProject());

    if (!context.isMake()) {
      flexCompilerHandler.quitCompilerShell();
      for (ProcessingItem item : items) {
        flexCompilerHandler.getCompilerDependenciesCache().markBCDirty(((MyProcessingItem)item).myModule, ((MyProcessingItem)item).myBC);
      }
    }

    if (flexCompilerConfiguration.USE_FCSH) {
      context.addMessage(CompilerMessageCategory.INFORMATION,
                         "FCSH tool is not supported yet. Please choose another compiler at File | Settings | Compiler | Flex Compiler",
                         null, -1, -1);
      return ProcessingItem.EMPTY_ARRAY;
    }
    else {
      boolean builtIn = flexCompilerConfiguration.USE_BUILT_IN_COMPILER;
      final Sdk commonSdk = getSdkIfSame(items);

      if (builtIn && commonSdk == null) {
        builtIn = false;
        flexCompilerHandler.getBuiltInFlexCompilerHandler().stopCompilerProcess();
        context.addMessage(CompilerMessageCategory.INFORMATION, FlexBundle.message("can.not.use.built.in.compiler.shell"), null, -1, -1);
      }

      context.addMessage(CompilerMessageCategory.INFORMATION,
                         FlexBundle.message(builtIn ? "using.builtin.compiler" : "using.mxmlc.compc",
                                            flexCompilerConfiguration.MAX_PARALLEL_COMPILATIONS), null, -1, -1);
      final Collection<FlexCompilationTask> compilationTasks = new ArrayList<FlexCompilationTask>();
      for (final ProcessingItem item : items) {
        final Collection<FlexIdeBuildConfiguration> dependencies = new HashSet<FlexIdeBuildConfiguration>();
        final FlexIdeBuildConfiguration bc = ((MyProcessingItem)item).myBC;

        for (final DependencyEntry entry : bc.getDependencies().getEntries()) {
          if (entry instanceof BuildConfigurationEntry) {
            final FlexIdeBuildConfiguration dependencyBC = ((BuildConfigurationEntry)entry).findBuildConfiguration();
            if (dependencyBC != null && !dependencyBC.isSkipCompile() &&
                entry.getDependencyType().getLinkageType() != LinkageType.LoadInRuntime) {
              dependencies.add(dependencyBC);
            }
          }
        }

        compilationTasks.add(builtIn ? new BuiltInCompilationTask(((MyProcessingItem)item).myModule, bc, dependencies)
                                     : new MxmlcCompcCompilationTask(((MyProcessingItem)item).myModule, bc, dependencies));

        if (BCUtils.canHaveRLMsAndRuntimeStylesheets(bc)) {
          for (FlexIdeBuildConfiguration.RLMInfo rlm : bc.getRLMs()) {
            final ModifiableFlexIdeBuildConfiguration rlmBC = Factory.getTemporaryCopyForCompilation(bc);

            rlmBC.setOutputType(OutputType.RuntimeLoadedModule);
            rlmBC.setOptimizeFor(rlm.OPTIMIZE ? bc.getName() : ""); // any not empty string means that need to optimize

            final String subdir = PathUtil.getParentPath(rlm.OUTPUT_FILE);
            final String outputFileName = PathUtil.getFileName(rlm.OUTPUT_FILE);

            rlmBC.setMainClass(rlm.MAIN_CLASS);
            rlmBC.setOutputFileName(outputFileName);

            if (!subdir.isEmpty()) {
              final String outputFolder = PathUtil.getParentPath(bc.getActualOutputFilePath());
              rlmBC.setOutputFolder(outputFolder + "/" + subdir);
            }


            rlmBC.setUseHtmlWrapper(false);

            rlmBC.setRLMs(Collections.<FlexIdeBuildConfiguration.RLMInfo>emptyList());
            rlmBC.setCssFilesToCompile(Collections.<String>emptyList());

            final ModifiableCompilerOptions compilerOptions = rlmBC.getCompilerOptions();
            compilerOptions.setResourceFilesMode(ResourceFilesMode.None);
            compilerOptions.setAdditionalOptions(FlexUtils.removeOptions(compilerOptions.getAdditionalOptions(), "link-report"));

            compilationTasks.add(builtIn ? new BuiltInCompilationTask(((MyProcessingItem)item).myModule, rlmBC, dependencies)
                                         : new MxmlcCompcCompilationTask(((MyProcessingItem)item).myModule, rlmBC, dependencies));
          }

          for (String cssPath : bc.getCssFilesToCompile()) {
            final VirtualFile cssFile = LocalFileSystem.getInstance().findFileByPath(cssPath);
            if (cssFile == null) continue;

            final ModifiableFlexIdeBuildConfiguration cssBC = Factory.getTemporaryCopyForCompilation(bc);
            cssBC.setOutputType(OutputType.Application);

            cssBC.setMainClass(cssPath);
            cssBC.setOutputFileName(FileUtil.getNameWithoutExtension(PathUtil.getFileName(cssPath)) + ".swf");

            VirtualFile root = ProjectRootManager.getInstance(context.getProject()).getFileIndex().getSourceRootForFile(cssFile);
            if (root == null) root = ProjectRootManager.getInstance(context.getProject()).getFileIndex().getContentRootForFile(cssFile);
            final String relativePath = root == null ? null : VfsUtilCore.getRelativePath(cssFile.getParent(), root, '/');
            if (!StringUtil.isEmpty(relativePath)) {
              final String outputFolder = PathUtil.getParentPath(bc.getActualOutputFilePath());
              cssBC.setOutputFolder(outputFolder + "/" + relativePath);
            }

            cssBC.setUseHtmlWrapper(false);
            cssBC.setRLMs(Collections.<FlexIdeBuildConfiguration.RLMInfo>emptyList());
            cssBC.setCssFilesToCompile(Collections.<String>emptyList());

            cssBC.getCompilerOptions().setResourceFilesMode(ResourceFilesMode.None);

            compilationTasks.add(builtIn ? new BuiltInCompilationTask(((MyProcessingItem)item).myModule, cssBC, dependencies)
                                         : new MxmlcCompcCompilationTask(((MyProcessingItem)item).myModule, cssBC, dependencies));
          }
        }
      }

      if (builtIn) {
        try {
          flexCompilerHandler.getBuiltInFlexCompilerHandler().startCompilerIfNeeded(commonSdk, context);
        }
        catch (IOException e) {
          context.addMessage(CompilerMessageCategory.ERROR, e.toString(), null, -1, -1);
          return ProcessingItem.EMPTY_ARRAY;
        }
      }

      new FlexCompilationManager(context, compilationTasks).compile();

      final int activeCompilationsNumber = flexCompilerHandler.getBuiltInFlexCompilerHandler().getActiveCompilationsNumber();
      if (activeCompilationsNumber != 0) {
        LOG.error(activeCompilationsNumber + " Flex compilation(s) are not finished!");
      }

      FlexCompilerHandler.deleteTempFlexUnitFiles(context);

      if (ApplicationManager.getApplication().isUnitTestMode()) {
        Function<CompilerMessage, String> toString = new Function<CompilerMessage, String>() {
          @Override
          public String fun(final CompilerMessage compilerMessage) {
            return compilerMessage.getMessage();
          }
        };

        StringBuilder s = new StringBuilder("Compiler errors:\n");
        s.append(StringUtil.join(context.getMessages(CompilerMessageCategory.ERROR), toString, "\n"));
        s.append("\nCompiler warnings:\n");
        s.append(StringUtil.join(context.getMessages(CompilerMessageCategory.WARNING), toString, "\n"));
        FlexCompilerHandler.getInstance(context.getProject()).setLastCompilationMessages(s.toString());
      }
      return items;
    }
  }

  @SuppressWarnings("ConstantConditions") // already checked in validateConfiguration()
  @Nullable
  private static Sdk getSdkIfSame(final ProcessingItem[] items) {
    final Sdk sdk = ((MyProcessingItem)items[0]).myBC.getSdk();

    for (int i = 1; i < items.length; i++) {
      if (!sdk.equals(((MyProcessingItem)items[i]).myBC.getSdk())) {
        return null;
      }
    }

    return sdk;
  }

  @NotNull
  public String getDescription() {
    return "ActionScript Compiler";
  }

  public boolean validateConfiguration(final CompileScope scope) {
    final Module[] modules = scope.getAffectedModules();
    final Project project = modules.length > 0 ? modules[0].getProject() : null;
    if (project == null) return true;

    final Collection<Trinity<Module, FlexIdeBuildConfiguration, FlashProjectStructureProblem>> problems =
      new ArrayList<Trinity<Module, FlexIdeBuildConfiguration, FlashProjectStructureProblem>>();

    try {
      final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCsToCompile = getModulesAndBCsToCompile(scope);
      for (final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : modulesAndBCsToCompile) {
        final Consumer<FlashProjectStructureProblem> errorConsumer = new Consumer<FlashProjectStructureProblem>() {
          public void consume(final FlashProjectStructureProblem problem) {
            problems.add(Trinity.create(moduleAndBC.first, moduleAndBC.second, problem));
          }
        };

        checkConfiguration(moduleAndBC.first, moduleAndBC.second, false, errorConsumer);

        if (moduleAndBC.second.getNature().isMobilePlatform() && moduleAndBC.second.getNature().isApp()) {
          final RunConfiguration runConfig = CompileStepBeforeRun.getRunConfiguration(scope);
          if (runConfig instanceof FlashRunConfiguration) {
            final FlashRunnerParameters params = ((FlashRunConfiguration)runConfig).getRunnerParameters();
            if (moduleAndBC.first.getName().equals(params.getModuleName()) &&
                moduleAndBC.second.getName().equals(params.getBCName()) &&
                params.getMobileRunTarget() == AirMobileRunTarget.AndroidDevice) {
              checkPackagingOptions(moduleAndBC.second.getAndroidPackagingOptions(), errorConsumer);
            }
          }
        }
      }
      checkSimilarOutputFiles(modulesAndBCsToCompile,
                              new Consumer<Trinity<Module, FlexIdeBuildConfiguration, FlashProjectStructureProblem>>() {
                                public void consume(final Trinity<Module, FlexIdeBuildConfiguration, FlashProjectStructureProblem> trinity) {
                                  problems.add(trinity);
                                }
                              });
    }
    catch (ConfigurationException e) {
      // can't happen because already checked at RunConfiguration.getState()
      Messages.showErrorDialog(project, e.getMessage(), FlexBundle.message("project.setup.problem.title"));
      return false;
    }

    if (!problems.isEmpty()) {
      final FlashProjectStructureErrorsDialog dialog = new FlashProjectStructureErrorsDialog(project, problems);
      dialog.show();
      if (dialog.isOK()) {
        ShowSettingsUtil.getInstance().editConfigurable(project, ProjectStructureConfigurable.getInstance(project));
      }
      return false;
    }

    return true;
  }

  private static boolean checkSimilarOutputFiles(final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCsToCompile,
                                                 final Consumer<Trinity<Module, FlexIdeBuildConfiguration, FlashProjectStructureProblem>> errorConsumer) {

    final Map<String, Pair<Module, FlexIdeBuildConfiguration>> outputPathToModuleAndBC =
      new THashMap<String, Pair<Module, FlexIdeBuildConfiguration>>();
    for (Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : modulesAndBCsToCompile) {
      final FlexIdeBuildConfiguration bc = moduleAndBC.second;
      final String outputFilePath = bc.getActualOutputFilePath();
      checkOutputPathUnique(outputFilePath, moduleAndBC, outputPathToModuleAndBC, errorConsumer);
    }
    return true;
  }

  private static void checkOutputPathUnique(final String outputPath,
                                            final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC,
                                            final Map<String, Pair<Module, FlexIdeBuildConfiguration>> outputPathToModuleAndBC,
                                            final Consumer<Trinity<Module, FlexIdeBuildConfiguration, FlashProjectStructureProblem>> errorConsumer) {
    final String caseAwarePath = SystemInfo.isFileSystemCaseSensitive ? outputPath : outputPath.toLowerCase();

    final Pair<Module, FlexIdeBuildConfiguration> existing = outputPathToModuleAndBC.put(caseAwarePath, moduleAndBC);
    if (existing != null) {
      final String message = FlexBundle.message("same.output.files", existing.second.getName(), existing.first.getName(),
                                                FileUtil.toSystemDependentName(outputPath));
      errorConsumer.consume(Trinity.create(moduleAndBC.first, moduleAndBC.second, FlashProjectStructureProblem
        .createGeneralOptionProblem(moduleAndBC.second.getName(), message, FlexIdeBCConfigurable.Location.OutputFileName)));
    }
  }

  static Collection<Pair<Module, FlexIdeBuildConfiguration>> getModulesAndBCsToCompile(final CompileScope scope)
    throws ConfigurationException {

    final Collection<Pair<Module, FlexIdeBuildConfiguration>> result = new HashSet<Pair<Module, FlexIdeBuildConfiguration>>();
    final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCsToCompile = scope.getUserData(MODULES_AND_BCS_TO_COMPILE);
    final RunConfiguration runConfiguration = CompileStepBeforeRun.getRunConfiguration(scope);

    if (modulesAndBCsToCompile != null) {
      for (Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : modulesAndBCsToCompile) {
        if (!moduleAndBC.second.isSkipCompile()) {
          final FlexIdeBuildConfiguration bcWithForcedDebugStatus = forceDebugStatus(moduleAndBC.first.getProject(), moduleAndBC.second);
          result.add(Pair.create(moduleAndBC.first, bcWithForcedDebugStatus));
          appendBCDependencies(result, moduleAndBC.first, moduleAndBC.second);
        }
      }
    }
    else if (runConfiguration instanceof FlashRunConfiguration || runConfiguration instanceof FlexUnitRunConfiguration) {
      final BCBasedRunnerParameters params = runConfiguration instanceof FlashRunConfiguration
                                             ? ((FlashRunConfiguration)runConfiguration).getRunnerParameters()
                                             : ((FlexUnitRunConfiguration)runConfiguration).getRunnerParameters();
      final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC;

      final Ref<RuntimeConfigurationError> exceptionRef = new Ref<RuntimeConfigurationError>();
      moduleAndBC = ApplicationManager.getApplication().runReadAction(new NullableComputable<Pair<Module, FlexIdeBuildConfiguration>>() {
        public Pair<Module, FlexIdeBuildConfiguration> compute() {
          try {
            return params.checkAndGetModuleAndBC(runConfiguration.getProject());
          }
          catch (RuntimeConfigurationError e) {
            exceptionRef.set(e);
            return null;
          }
        }
      });
      if (!exceptionRef.isNull()) {
        throw new ConfigurationException(exceptionRef.get().getMessage(),
                                         FlexBundle.message("run.configuration.0", runConfiguration.getName()));
      }

      if (!moduleAndBC.second.isSkipCompile()) {
        result.add(moduleAndBC);
        appendBCDependencies(result, moduleAndBC.first, moduleAndBC.second);
      }
    }
    else {
      for (final Module module : scope.getAffectedModules()) {
        if (ModuleType.get(module) != FlexModuleType.getInstance()) continue;
        for (final FlexIdeBuildConfiguration bc : FlexBuildConfigurationManager.getInstance(module).getBuildConfigurations()) {
          if (!bc.isSkipCompile()) {
            result.add(Pair.create(module, bc));
          }
        }
      }
    }

    return result;
  }

  private static FlexIdeBuildConfiguration forceDebugStatus(final Project project, final FlexIdeBuildConfiguration bc) {
    final boolean debug;

    if (bc.getTargetPlatform() == TargetPlatform.Mobile) {
      final AirPackageProjectParameters params = AirPackageProjectParameters.getInstance(project);
      if (bc.getAndroidPackagingOptions().isEnabled()) {
        debug = params.androidPackageType != AirPackageProjectParameters.AndroidPackageType.Release;
      }
      else {
        debug = params.iosPackageType == AirPackageProjectParameters.IOSPackageType.DebugOverNetwork;
      }
    }
    else {
      debug = false;
    }

    // must not use getTemporaryCopyForCompilation() here because additional config file must not be merged with the generated one when compiling swf for release or AIR package
    final ModifiableFlexIdeBuildConfiguration result = Factory.getCopy(bc);
    final String additionalOptions = FlexUtils.removeOptions(bc.getCompilerOptions().getAdditionalOptions(), "debug", "compiler.debug");
    result.getCompilerOptions().setAdditionalOptions(additionalOptions + " -debug=" + String.valueOf(debug));

    return result;
  }

  private static void appendBCDependencies(final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCs,
                                           final Module module,
                                           final FlexIdeBuildConfiguration bc) throws ConfigurationException {
    for (final DependencyEntry entry : bc.getDependencies().getEntries()) {
      if (entry instanceof BuildConfigurationEntry) {
        final BuildConfigurationEntry bcEntry = (BuildConfigurationEntry)entry;

        final Module dependencyModule = bcEntry.findModule();
        final FlexIdeBuildConfiguration dependencyBC = dependencyModule == null ? null : bcEntry.findBuildConfiguration();

        if (dependencyModule == null || dependencyBC == null) {
          throw new ConfigurationException(FlexBundle.message("bc.dependency.does.not.exist", bcEntry.getBcName(), bcEntry.getModuleName(),
                                                              bc.getName(), module.getName()));
        }

        final Pair<Module, FlexIdeBuildConfiguration> dependencyModuleAndBC = Pair.create(dependencyModule, dependencyBC);
        if (!dependencyBC.isSkipCompile()) {
          if (modulesAndBCs.add(dependencyModuleAndBC)) {
            appendBCDependencies(modulesAndBCs, dependencyModule, dependencyBC);
          }
        }
      }
    }
  }

  public static void checkConfiguration(final Module module,
                                        final FlexIdeBuildConfiguration bc,
                                        final boolean checkPackaging,
                                        final Consumer<FlashProjectStructureProblem> errorConsumer) {
    final Sdk sdk = bc.getSdk();
    if (sdk == null) {
      errorConsumer.consume(FlashProjectStructureProblem.createDependenciesProblem(FlexBundle.message("sdk.not.set"),
                                                                                   DependenciesConfigurable.Location.SDK));
    }

    if (sdk != null &&
        (StringUtil.compareVersionNumbers(sdk.getVersionString(), "0") < 0 ||
         StringUtil.compareVersionNumbers(sdk.getVersionString(), "100") > 0)) {
      errorConsumer.consume(FlashProjectStructureProblem.createDependenciesProblem(FlexBundle.message("sdk.version.unknown", sdk.getName()),
                                                                                   DependenciesConfigurable.Location.SDK));
    }

    InfoFromConfigFile info = InfoFromConfigFile.DEFAULT;

    final String additionalConfigFilePath = bc.getCompilerOptions().getAdditionalConfigFilePath();
    if (!additionalConfigFilePath.isEmpty()) {
      final VirtualFile additionalConfigFile = LocalFileSystem.getInstance().findFileByPath(additionalConfigFilePath);
      if (additionalConfigFile == null || additionalConfigFile.isDirectory()) {
        errorConsumer.consume(FlashProjectStructureProblem.createCompilerOptionsProblem(
          FlexBundle.message("additional.config.file.not.found", FileUtil.toSystemDependentName(additionalConfigFilePath)),
          CompilerOptionsConfigurable.Location.AdditonalConfigFile));
      }
      if (!bc.isTempBCForCompilation()) {
        info = FlexCompilerConfigFileUtil.getInfoFromConfigFile(additionalConfigFilePath);
      }
    }

    final BuildConfigurationNature nature = bc.getNature();

    if (!nature.isLib() && info.getMainClass(module) == null && !bc.isTempBCForCompilation()) {
      if (bc.getMainClass().isEmpty()) {
        errorConsumer
          .consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle.message("main.class.not.set"),
                                                                           FlexIdeBCConfigurable.Location.MainClass));
      }
      else {
        if (FlexUtils.getPathToMainClassFile(bc.getMainClass(), module).isEmpty()) {
          errorConsumer.consume(FlashProjectStructureProblem
                                  .createGeneralOptionProblem(bc.getName(), FlexBundle.message("main.class.not.found", bc.getMainClass()),
                                                              FlexIdeBCConfigurable.Location.MainClass));
        }
      }
    }

    if (info.getOutputFileName() == null && info.getOutputFolderPath() == null) {
      if (bc.getOutputFileName().isEmpty()) {
        errorConsumer.consume(FlashProjectStructureProblem
                                .createGeneralOptionProblem(bc.getName(), FlexBundle.message("output.file.name.not.set"),
                                                            FlexIdeBCConfigurable.Location.OutputFileName));
      }

      if (!nature.isLib() && !bc.getOutputFileName().toLowerCase().endsWith(".swf")) {
        errorConsumer.consume(
          FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle.message("output.file.wrong.extension", "swf"),
                                                                  FlexIdeBCConfigurable.Location.OutputFileName));
      }

      if (nature.isLib() && !bc.getOutputFileName().toLowerCase().endsWith(".swc")) {
        errorConsumer.consume(
          FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle.message("output.file.wrong.extension", "swc"),
                                                                  FlexIdeBCConfigurable.Location.OutputFileName));
      }

      if (bc.getOutputFolder().isEmpty()) {
        errorConsumer.consume(FlashProjectStructureProblem
                                .createGeneralOptionProblem(bc.getName(), FlexBundle.message("output.folder.not.set"),
                                                            FlexIdeBCConfigurable.Location.OutputFolder));
      }
      else if (!FileUtil.isAbsolute(bc.getOutputFolder())) {
        errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
          .message("output.folder.not.absolute", FileUtil.toSystemDependentName(bc.getOutputFolder())),
                                                                                      FlexIdeBCConfigurable.Location.OutputFolder));
      }
    }

    if (nature.isWebPlatform() && nature.isApp() && bc.isUseHtmlWrapper()) {
      if (bc.getWrapperTemplatePath().isEmpty()) {
        errorConsumer
          .consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle.message("html.template.folder.not.set"),
                                                                           FlexIdeBCConfigurable.Location.HtmlTemplatePath));
      }
      else {
        final VirtualFile templateDir = LocalFileSystem.getInstance().findFileByPath(bc.getWrapperTemplatePath());
        if (templateDir == null || !templateDir.isDirectory()) {
          errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
            .message("html.template.folder.not.found", FileUtil.toSystemDependentName(bc.getWrapperTemplatePath())),
                                                                                        FlexIdeBCConfigurable.Location.HtmlTemplatePath));
        }
        else {
          final VirtualFile templateFile = templateDir.findChild(CreateHtmlWrapperTemplateDialog.HTML_WRAPPER_TEMPLATE_FILE_NAME);
          if (templateFile == null) {
            errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
              .message("no.index.template.html.file", templateDir.getPresentableUrl()), FlexIdeBCConfigurable.Location.HtmlTemplatePath));
          }
          else {
            // Probably heavy calculation. Will be checked only when real html template handling is performed
            /*
            try {
              if (!VfsUtilCore.loadText(templateFile).contains(FlexCompilationUtils.SWF_MACRO)) {
                errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(
                  FlexBundle.message("no.swf.macro.in.template", FileUtil.toSystemDependentName(templateFile.getPath())), "html.template"));
              }
            }
            catch (IOException e) {
              errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(
                FlexBundle.message("failed.to.load.template.file", FileUtil.toSystemDependentName(templateFile.getPath()), e.getMessage()),
                "html.template"));
            }
            */

            final String templateFolderPath = templateDir.getPath();
            boolean ok = true;

            for (String url : ModuleRootManager.getInstance(module).getContentRootUrls()) {
              if (ok) {
                ok = checkWrapperFolderClash(bc, templateFolderPath, VfsUtilCore.urlToPath(url), "module content root", errorConsumer);
              }
            }

            for (String url : ModuleRootManager.getInstance(module).getSourceRootUrls()) {
              if (ok) {
                ok = checkWrapperFolderClash(bc, templateFolderPath, VfsUtilCore.urlToPath(url), "source folder", errorConsumer);
              }
            }

            final String outputFolderPath = StringUtil.notNullize(info.getOutputFolderPath(), bc.getOutputFolder());
            if (ok && !outputFolderPath.isEmpty()) {
              ok = checkWrapperFolderClash(bc, templateFolderPath, outputFolderPath, "output folder", errorConsumer);
            }
          }
        }
      }
    }

    if (BCUtils.canHaveRLMsAndRuntimeStylesheets(bc)) {
      for (FlexIdeBuildConfiguration.RLMInfo rlm : bc.getRLMs()) {
        if (rlm.MAIN_CLASS.isEmpty()) {
          errorConsumer
            .consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle.message("rlm.main.class.not.set"),
                                                                             FlexIdeBCConfigurable.Location.RLMs));
        }
        else {
          if (FlexUtils.getPathToMainClassFile(rlm.MAIN_CLASS, module).isEmpty()) {
            errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
              .message("rlm.main.class.not.found", rlm.MAIN_CLASS), FlexIdeBCConfigurable.Location.RLMs));
          }
        }

        if (bc.getMainClass().equals(rlm.MAIN_CLASS)) {
          errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
            .message("rlm.main.class.equal.to.bc.main.class", rlm.MAIN_CLASS), FlexIdeBCConfigurable.Location.RLMs));
        }

        if (bc.getOutputFileName().equals(rlm.OUTPUT_FILE)) {
          errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
            .message("rlm.output.equal.to.bc.output", rlm.OUTPUT_FILE), FlexIdeBCConfigurable.Location.RLMs));
        }

        if (rlm.OUTPUT_FILE.isEmpty()) {
          errorConsumer.consume(FlashProjectStructureProblem
                                  .createGeneralOptionProblem(bc.getName(), FlexBundle.message("rlm.output.file.name.not.specified"),
                                                              FlexIdeBCConfigurable.Location.RLMs));
        }
        else {
          if (!rlm.OUTPUT_FILE.toLowerCase().endsWith(".swf")) {
            errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle.message(
              "rlm.output.file.must.have.swf.extension"), FlexIdeBCConfigurable.Location.RLMs));
          }
        }
      }

      for (String cssPath : bc.getCssFilesToCompile()) {
        if (!cssPath.toLowerCase().endsWith(".css")) {
          errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
            .message("not.a.css.runtime.stylesheet", FileUtil.toSystemDependentName(cssPath)),
                                                                                        FlexIdeBCConfigurable.Location.RuntimeStyleSheets));
        }
        else if (LocalFileSystem.getInstance().findFileByPath(cssPath) == null) {
          errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle
            .message("css.not.found", FileUtil.toSystemDependentName(cssPath)), FlexIdeBCConfigurable.Location.RuntimeStyleSheets));
        }
      }
    }

    if (nature.isLib()) {
      for (String path : bc.getCompilerOptions().getFilesToIncludeInSWC()) {
        if (LocalFileSystem.getInstance().findFileByPath(path) == null) {
          errorConsumer.consume(FlashProjectStructureProblem.createCompilerOptionsProblem(
            FlexBundle.message("file.to.include.in.swc.not.found", FileUtil.toSystemDependentName(path)),
            CompilerOptionsConfigurable.Location.FilesToIncludeInSwc));
        }
      }
    }

    if (checkPackaging) {
      checkPackagingOptions(bc, errorConsumer);
    }

    //checkDependencies(moduleName, bc);
  }

  /* This verification is disabled because Vladimir Krivosheev has app on app dependency because he needs predictable compilation order.
   So we do not check dependencies and ignore incompatible ones when doing highlighting and compilation. */
  private static void checkDependencies(final String moduleName, final FlexIdeBuildConfiguration bc) throws ConfigurationException {
    for (final DependencyEntry entry : bc.getDependencies().getEntries()) {
      if (entry instanceof BuildConfigurationEntry) {
        final BuildConfigurationEntry bcEntry = (BuildConfigurationEntry)entry;
        final FlexIdeBuildConfiguration dependencyBC = bcEntry.findBuildConfiguration();
        final LinkageType linkageType = bcEntry.getDependencyType().getLinkageType();

        if (dependencyBC == null) {
          throw new ConfigurationException(
            FlexBundle.message("bc.dependency.does.not.exist", bcEntry.getBcName(), bcEntry.getModuleName(), bc.getName(), moduleName));
        }

        if (!checkDependencyType(bc, dependencyBC, linkageType)) {
          throw new ConfigurationException(
            FlexBundle.message("bc.dependency.problem",
                               bc.getName(), moduleName, bc.getOutputType().getPresentableText(),
                               dependencyBC.getName(), bcEntry.getModuleName(), dependencyBC.getOutputType().getPresentableText(),
                               linkageType.getShortText()));
        }
      }
    }
  }

  private static boolean checkWrapperFolderClash(final FlexIdeBuildConfiguration bc,
                                                 final String templateFolderPath,
                                                 final String otherFolderPath,
                                                 final String otherFolderDescription,
                                                 final Consumer<FlashProjectStructureProblem> errorConsumer) {
    if (FileUtil.isAncestor(templateFolderPath, otherFolderPath, false)) {
      errorConsumer.consume(FlashProjectStructureProblem.createGeneralOptionProblem(bc.getName(), FlexBundle.message(
        "html.wrapper.folder.clash", otherFolderDescription, FileUtil.toSystemDependentName(templateFolderPath)),
                                                                                    FlexIdeBCConfigurable.Location.HtmlTemplatePath));
      return false;
    }
    return true;
  }

  public static void checkPackagingOptions(final FlexIdeBuildConfiguration bc, final Consumer<FlashProjectStructureProblem> errorConsumer) {
    if (bc.getOutputType() != OutputType.Application) return;

    if (bc.getTargetPlatform() == TargetPlatform.Desktop) {
      checkPackagingOptions(bc.getAirDesktopPackagingOptions(), errorConsumer);
    }
    else if (bc.getTargetPlatform() == TargetPlatform.Mobile) {
      if (bc.getAndroidPackagingOptions().isEnabled()) {
        checkPackagingOptions(bc.getAndroidPackagingOptions(), errorConsumer);
      }
      if (bc.getIosPackagingOptions().isEnabled()) {
        checkPackagingOptions(bc.getIosPackagingOptions(), errorConsumer);
      }
    }
  }

  private static void checkPackagingOptions(final AirPackagingOptions packagingOptions,
                                            final Consumer<FlashProjectStructureProblem> errorConsumer) {
    final String device = packagingOptions instanceof AndroidPackagingOptions
                          ? "Android"
                          : packagingOptions instanceof IosPackagingOptions
                            ? "iOS"
                            : "";
    if (!packagingOptions.isUseGeneratedDescriptor()) {
      if (packagingOptions.getCustomDescriptorPath().isEmpty()) {
        errorConsumer.consume(FlashProjectStructureProblem
                                .createPackagingOptionsProblem(packagingOptions, FlexBundle.message("custom.descriptor.not.set", device),
                                                               AirPackagingConfigurableBase.Location.CustomDescriptor));
      }
      else {
        final VirtualFile descriptorFile = LocalFileSystem.getInstance().findFileByPath(packagingOptions.getCustomDescriptorPath());
        if (descriptorFile == null || descriptorFile.isDirectory()) {
          errorConsumer.consume(FlashProjectStructureProblem
                                  .createPackagingOptionsProblem(packagingOptions, FlexBundle
                                    .message("custom.descriptor.not.found", device,
                                             FileUtil.toSystemDependentName(packagingOptions.getCustomDescriptorPath())),
                                                                 AirPackagingConfigurableBase.Location.CustomDescriptor));
        }
      }
    }

    if (packagingOptions.getPackageFileName().isEmpty()) {
      errorConsumer.consume(FlashProjectStructureProblem.createPackagingOptionsProblem(packagingOptions, FlexBundle
        .message("package.file.name.not.set", device), AirPackagingConfigurableBase.Location.PackageFileName));
    }

    for (FilePathAndPathInPackage entry : packagingOptions.getFilesToPackage()) {
      final String fullPath = entry.FILE_PATH;
      String relPathInPackage = entry.PATH_IN_PACKAGE;
      if (relPathInPackage.startsWith("/")) {
        relPathInPackage = relPathInPackage.substring(1);
      }

      if (fullPath.isEmpty()) {
        errorConsumer.consume(FlashProjectStructureProblem.createPackagingOptionsProblem(packagingOptions, FlexBundle
          .message("packaging.options.empty.file.name", device), AirPackagingConfigurableBase.Location.FilesToPackage));
      }
      else {
        final VirtualFile file = LocalFileSystem.getInstance().findFileByPath(fullPath);
        if (file == null) {
          errorConsumer.consume(FlashProjectStructureProblem
                                  .createPackagingOptionsProblem(packagingOptions, FlexBundle
                                    .message("packaging.options.file.not.found", device, FileUtil.toSystemDependentName(fullPath)),
                                                                 AirPackagingConfigurableBase.Location.FilesToPackage));
        }

        if (relPathInPackage.length() == 0) {
          errorConsumer.consume(FlashProjectStructureProblem.createPackagingOptionsProblem(packagingOptions, FlexBundle
            .message("packaging.options.empty.relative.path", device), AirPackagingConfigurableBase.Location.FilesToPackage));
        }

        if (file != null && file.isDirectory() && !fullPath.endsWith("/" + relPathInPackage)) {
          errorConsumer.consume(FlashProjectStructureProblem
                                  .createPackagingOptionsProblem(packagingOptions, FlexBundle
                                    .message("packaging.options.relative.path.not.matches", device,
                                             FileUtil.toSystemDependentName(relPathInPackage)),
                                                                 AirPackagingConfigurableBase.Location.FilesToPackage));
        }
      }
    }

    final AirSigningOptions signingOptions = packagingOptions.getSigningOptions();
    if (packagingOptions instanceof IosPackagingOptions) {
      final String provisioningProfilePath = signingOptions.getProvisioningProfilePath();
      if (provisioningProfilePath.isEmpty()) {
        errorConsumer.consume(FlashProjectStructureProblem.createPackagingOptionsProblem(packagingOptions, FlexBundle
          .message("ios.provisioning.profile.not.set"), AirPackagingConfigurableBase.Location.ProvisioningProfile));
      }
      else {
        final VirtualFile provisioningProfile = LocalFileSystem.getInstance().findFileByPath(provisioningProfilePath);
        if (provisioningProfile == null || provisioningProfile.isDirectory()) {
          errorConsumer.consume(FlashProjectStructureProblem
                                  .createPackagingOptionsProblem(packagingOptions, FlexBundle
                                    .message("ios.provisioning.profile.not.found", FileUtil.toSystemDependentName(provisioningProfilePath)),
                                                                 AirPackagingConfigurableBase.Location.ProvisioningProfile));
        }
      }
    }

    final boolean tempCertificate = !(packagingOptions instanceof IosPackagingOptions) && signingOptions.isUseTempCertificate();
    if (!tempCertificate) {
      final String keystorePath = signingOptions.getKeystorePath();
      if (keystorePath.isEmpty()) {
        errorConsumer.consume(FlashProjectStructureProblem.createPackagingOptionsProblem(packagingOptions,
                                                                                         FlexBundle.message("keystore.not.set", device),
                                                                                         AirPackagingConfigurableBase.Location.Keystore));
      }
      else {
        final VirtualFile keystore = LocalFileSystem.getInstance().findFileByPath(keystorePath);
        if (keystore == null || keystore.isDirectory()) {
          errorConsumer.consume(FlashProjectStructureProblem
                                  .createPackagingOptionsProblem(packagingOptions, FlexBundle
                                    .message("keystore.not.found", device, FileUtil.toSystemDependentName(keystorePath)),
                                                                 AirPackagingConfigurableBase.Location.Keystore));
        }
      }
    }
  }

  public static boolean checkDependencyType(final FlexIdeBuildConfiguration bc,
                                            final FlexIdeBuildConfiguration dependencyBC,
                                            final LinkageType linkageType) {
    final boolean ok;

    switch (dependencyBC.getOutputType()) {
      case Application:
        ok = false;
        break;
      case RuntimeLoadedModule:
        ok = bc.getOutputType() == OutputType.Application && linkageType == LinkageType.LoadInRuntime;
        break;
      case Library:
        ok = ArrayUtil.contains(linkageType, LinkageType.getSwcLinkageValues());
        break;
      default:
        assert false;
        ok = false;
    }

    return ok;
  }

  public ValidityState createValidityState(final DataInput in) throws IOException {
    return new EmptyValidityState();
  }

  private static class MyProcessingItem implements ProcessingItem {
    private final Module myModule;
    private final FlexIdeBuildConfiguration myBC;

    private MyProcessingItem(final Module module, final FlexIdeBuildConfiguration bc) {
      myModule = module;
      myBC = bc;
    }

    @NotNull
    public VirtualFile getFile() {
      return myModule.getModuleFile();
    }

    public ValidityState getValidityState() {
      return new EmptyValidityState();
    }
  }
}
