package com.intellij.lang.javascript.flex.projectStructure.ui;

import com.intellij.execution.ExecutionBundle;
import com.intellij.ide.ui.ListCellRendererWrapper;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.FlexUtils;
import com.intellij.lang.javascript.flex.build.FlexCompilerConfigFileUtil;
import com.intellij.lang.javascript.flex.build.InfoFromConfigFile;
import com.intellij.lang.javascript.flex.projectStructure.CompilerOptionInfo;
import com.intellij.lang.javascript.flex.projectStructure.model.ModifiableFlexIdeBuildConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.model.OutputType;
import com.intellij.lang.javascript.flex.projectStructure.model.TargetPlatform;
import com.intellij.lang.javascript.flex.projectStructure.model.impl.FlexProjectConfigurationEditor;
import com.intellij.lang.javascript.flex.projectStructure.options.BCUtils;
import com.intellij.lang.javascript.flex.projectStructure.options.BuildConfigurationNature;
import com.intellij.lang.javascript.flex.sdk.FlexSdkUtils;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.lang.javascript.refactoring.ui.JSReferenceEditor;
import com.intellij.lang.javascript.ui.JSClassChooserDialog;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.ui.configuration.ModuleEditor;
import com.intellij.openapi.roots.ui.configuration.ModulesConfigurator;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ModuleStructureConfigurable;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectStructureElementConfigurable;
import com.intellij.openapi.roots.ui.configuration.projectRoot.StructureConfigurableContext;
import com.intellij.openapi.roots.ui.configuration.projectRoot.daemon.ProjectStructureElement;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.NamedConfigurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.*;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import com.intellij.util.PlatformIcons;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.HyperlinkEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;

public class FlexIdeBCConfigurable extends ProjectStructureElementConfigurable<ModifiableFlexIdeBuildConfiguration>
  implements CompositeConfigurable.Item {

  public static final String TAB_NAME = FlexBundle.message("bc.tab.general.display.name");
  private JPanel myMainPanel;

  private JLabel myNatureLabel;
  private HoverHyperlinkLabel myChangeNatureHyperlink;

  private JTextField myNameField;

  private JPanel myOptimizeForPanel;
  private JComboBox myOptimizeForCombo;

  private JLabel myMainClassLabel;
  private JSReferenceEditor myMainClassComponent;
  private JLabel myMainClassWarning;
  private JTextField myOutputFileNameTextField;
  private JLabel myOutputFileNameWarning;
  private TextFieldWithBrowseButton myOutputFolderField;
  private JLabel myOutputFolderWarning;

  private JPanel myHtmlWrapperPanel;
  private JCheckBox myUseHTMLWrapperCheckBox;
  private JLabel myWrapperFolderLabel;
  private TextFieldWithBrowseButton myWrapperTemplateTextWithBrowse;
  private JButton myCreateHtmlWrapperTemplateButton;

  private JLabel myCssFilesLabel;
  private TextFieldWithBrowseButton.NoPathCompletion myCssFilesTextWithBrowse;
  private Collection<String> myCssFilesToCompile;

  private JCheckBox mySkipCompilationCheckBox;
  private JLabel myWarning;

  private final Module myModule;
  private final ModifiableFlexIdeBuildConfiguration myConfiguration;
  private @NotNull final FlexProjectConfigurationEditor myConfigEditor;
  private final ProjectSdksModel mySdksModel;
  private final StructureConfigurableContext myContext;
  private String myName;

  private DependenciesConfigurable myDependenciesConfigurable;
  private CompilerOptionsConfigurable myCompilerOptionsConfigurable;
  private @Nullable AirDesktopPackagingConfigurable myAirDesktopPackagingConfigurable;
  private @Nullable AndroidPackagingConfigurable myAndroidPackagingConfigurable;
  private @Nullable IOSPackagingConfigurable myIOSPackagingConfigurable;

  private final BuildConfigurationProjectStructureElement myStructureElement;

  private final Disposable myDisposable;

  private final UserActivityListener myUserActivityListener;
  private boolean myFreeze;

  private JSClassChooserDialog.PublicInheritor myMainClassFilter;

  public FlexIdeBCConfigurable(final Module module,
                               final ModifiableFlexIdeBuildConfiguration bc,
                               final Runnable bcNatureModifier,
                               final @NotNull FlexProjectConfigurationEditor configEditor,
                               final ProjectSdksModel sdksModel,
                               final StructureConfigurableContext context) {
    super(false, null);
    myModule = module;
    myConfiguration = bc;
    myConfigEditor = configEditor;
    mySdksModel = sdksModel;
    myContext = context;
    myName = bc.getName();

    myStructureElement = new BuildConfigurationProjectStructureElement(bc, module, context) {
      @Override
      protected void libraryReplaced(@NotNull final Library library, @Nullable final Library replacement) {
        myDependenciesConfigurable.libraryReplaced(library, replacement);
      }
    };
    myCssFilesToCompile = Collections.emptyList();

    myDisposable = Disposer.newDisposable();

    myUserActivityListener = new UserActivityListener() {
      @Override
      public void stateChanged() {
        if (myFreeze) {
          return;
        }

        try {
          apply();
        }
        catch (ConfigurationException ignored) {
        }
        myContext.getDaemonAnalyzer().queueUpdate(myStructureElement);
      }
    };

    final UserActivityWatcher watcher = new UserActivityWatcher();
    watcher.register(myMainPanel);
    watcher.addUserActivityListener(myUserActivityListener, myDisposable);

    createChildConfigurables();

    myChangeNatureHyperlink.addHyperlinkListener(new HyperlinkAdapter() {
      protected void hyperlinkActivated(final HyperlinkEvent e) {
        bcNatureModifier.run();
      }
    });

    myNameField.getDocument().addDocumentListener(new DocumentAdapter() {
      protected void textChanged(DocumentEvent e) {
        setDisplayName(myNameField.getText().trim());
      }
    });

    myOutputFolderField.addBrowseFolderListener(null, null, module.getProject(),
                                                FileChooserDescriptorFactory.createSingleFolderDescriptor());

    myUseHTMLWrapperCheckBox.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        updateControls();
        IdeFocusManager.getInstance(module.getProject()).requestFocus(myWrapperTemplateTextWithBrowse.getTextField(), true);
      }
    });

    final String title = "Select folder with HTML wrapper template";
    final String description = "Folder must contain 'index.template.html' file which must contain '${swf}' macro.";
    myWrapperTemplateTextWithBrowse.addBrowseFolderListener(title, description, module.getProject(),
                                                            FileChooserDescriptorFactory.createSingleFolderDescriptor());

    myCreateHtmlWrapperTemplateButton.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final Sdk sdk = myDependenciesConfigurable.getCurrentSdk();
        if (sdk == null) {
          Messages.showInfoMessage(myModule.getProject(), FlexBundle.message("sdk.needed.to.create.wrapper"),
                                   CreateHtmlWrapperTemplateDialog.TITLE);
        }
        else {
          String path = myWrapperTemplateTextWithBrowse.getText().trim();
          if (path.isEmpty()) {
            path = FlexUtils.getContentOrModuleFolderPath(module) + "/" + CreateHtmlWrapperTemplateDialog.HTML_TEMPLATE_FOLDER_NAME;
          }
          final CreateHtmlWrapperTemplateDialog dialog = new CreateHtmlWrapperTemplateDialog(module, sdk, path);
          dialog.show();
          if (dialog.isOK()) {
            myWrapperTemplateTextWithBrowse.setText(FileUtil.toSystemDependentName(dialog.getWrapperFolderPath()));
          }
        }
      }
    });

    myCssFilesTextWithBrowse.getTextField().setEditable(false);
    myCssFilesTextWithBrowse.setButtonIcon(PlatformIcons.OPEN_EDIT_DIALOG_ICON);
    myCssFilesTextWithBrowse.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final List<StringBuilder> value = new ArrayList<StringBuilder>();
        for (String cssFilePath : myCssFilesToCompile) {
          value.add(new StringBuilder(cssFilePath));
        }
        final RepeatableValueDialog dialog =
          new RepeatableValueDialog(module.getProject(), FlexBundle.message("css.files.to.compile.dialog.title"), value,
                                    CompilerOptionInfo.CSS_FILES_INFO_FOR_UI);
        dialog.show();
        if (dialog.isOK()) {
          final List<StringBuilder> newValue = dialog.getCurrentList();
          myCssFilesToCompile = new ArrayList<String>(newValue.size());
          for (StringBuilder cssPath : newValue) {
            myCssFilesToCompile.add(cssPath.toString());
          }
          updateCssFilesText();
        }
      }
    });

    myOptimizeForCombo.setModel(new CollectionComboBoxModel(Arrays.asList(""), ""));
    myOptimizeForCombo.setRenderer(new ListCellRendererWrapper(myOptimizeForCombo.getRenderer()) {
      @Override
      public void customize(JList list, Object value, int index, boolean selected, boolean hasFocus) {
        if ("".equals(value)) {
          setText("<no optimization>");
        }
      }
    });


    myMainClassWarning.setIcon(IconLoader.getIcon("smallWarning.png"));
    myOutputFileNameWarning.setIcon(IconLoader.getIcon("smallWarning.png"));
    myOutputFolderWarning.setIcon(IconLoader.getIcon("smallWarning.png"));

    myWarning.setIcon(UIUtil.getBalloonWarningIcon());
  }

  public void createChildConfigurables() {
    final BuildConfigurationNature nature = myConfiguration.getNature();

    if (myDependenciesConfigurable != null) {
      myDependenciesConfigurable.removeUserActivityListeners();
      myDependenciesConfigurable.disposeUIResources();
    }
    if (myCompilerOptionsConfigurable != null) {
      myCompilerOptionsConfigurable.removeUserActivityListeners();
      myCompilerOptionsConfigurable.disposeUIResources();
    }
    if (myAirDesktopPackagingConfigurable != null) {
      myAirDesktopPackagingConfigurable.removeUserActivityListeners();
      myAirDesktopPackagingConfigurable.disposeUIResources();
    }
    if (myAndroidPackagingConfigurable != null) {
      myAndroidPackagingConfigurable.removeUserActivityListeners();
      myAndroidPackagingConfigurable.disposeUIResources();
    }
    if (myIOSPackagingConfigurable != null) {
      myIOSPackagingConfigurable.removeUserActivityListeners();
      myIOSPackagingConfigurable.disposeUIResources();
    }

    myDependenciesConfigurable = new DependenciesConfigurable(myConfiguration, myModule.getProject(), myConfigEditor, mySdksModel);
    myDependenciesConfigurable.addUserActivityListener(myUserActivityListener, myDisposable);

    myCompilerOptionsConfigurable =
      new CompilerOptionsConfigurable(myModule, nature, myDependenciesConfigurable, myConfiguration.getCompilerOptions());
    myCompilerOptionsConfigurable.addUserActivityListener(myUserActivityListener, myDisposable);

    myCompilerOptionsConfigurable.addAdditionalOptionsListener(new CompilerOptionsConfigurable.OptionsListener() {
      public void configFileChanged(final String additionalConfigFilePath) {
        checkIfConfigFileOverridesOptions(additionalConfigFilePath);
      }

      public void additionalOptionsChanged(final String additionalOptions) {
        // may be parse additionalOptions in the same way as config file
      }
    });

    final Computable<String> mainClassComputable = new Computable<String>() {
      public String compute() {
        return myMainClassComponent.getText().trim();
      }
    };
    final Computable<String> airVersionComputable = new Computable<String>() {
      public String compute() {
        final Sdk sdk = myDependenciesConfigurable.getCurrentSdk();
        return sdk == null ? "" : FlexSdkUtils.getAirVersion(sdk.getVersionString());
      }
    };
    final Computable<Boolean> androidEnabledComputable = new Computable<Boolean>() {
      public Boolean compute() {
        return myAndroidPackagingConfigurable != null && myAndroidPackagingConfigurable.isPackagingEnabled();
      }
    };
    final Computable<Boolean> iosEnabledComputable = new Computable<Boolean>() {
      public Boolean compute() {
        return myIOSPackagingConfigurable != null && myIOSPackagingConfigurable.isPackagingEnabled();
      }
    };
    final Consumer<String> createdDescriptorConsumer = new Consumer<String>() {
      // called only for mobile projects if generated descriptor contains both Android and iOS
      public void consume(final String descriptorPath) {
        assert myAndroidPackagingConfigurable != null && myIOSPackagingConfigurable != null;
        myAndroidPackagingConfigurable.setUseCustomDescriptor(descriptorPath);
        myIOSPackagingConfigurable.setUseCustomDescriptor(descriptorPath);
      }
    };

    myAirDesktopPackagingConfigurable = nature.isDesktopPlatform() && nature.isApp()
                                        ? new AirDesktopPackagingConfigurable(myModule, myConfiguration.getAirDesktopPackagingOptions(),
                                                                              mainClassComputable, airVersionComputable,
                                                                              androidEnabledComputable, iosEnabledComputable,
                                                                              createdDescriptorConsumer)
                                        : null;
    if (myAirDesktopPackagingConfigurable != null) {
      myAirDesktopPackagingConfigurable.addUserActivityListener(myUserActivityListener, myDisposable);
    }

    myAndroidPackagingConfigurable = nature.isMobilePlatform() && nature.isApp()
                                     ? new AndroidPackagingConfigurable(myModule, myConfiguration.getAndroidPackagingOptions(),
                                                                        mainClassComputable, airVersionComputable, androidEnabledComputable,
                                                                        iosEnabledComputable, createdDescriptorConsumer)
                                     : null;
    if (myAndroidPackagingConfigurable != null) {
      myAndroidPackagingConfigurable.addUserActivityListener(myUserActivityListener, myDisposable);
    }

    myIOSPackagingConfigurable = nature.isMobilePlatform() && nature.isApp()
                                 ? new IOSPackagingConfigurable(myModule, myConfiguration.getIosPackagingOptions(), mainClassComputable,
                                                                airVersionComputable, androidEnabledComputable, iosEnabledComputable,
                                                                createdDescriptorConsumer)
                                 : null;
    if (myIOSPackagingConfigurable != null) {
      myIOSPackagingConfigurable.addUserActivityListener(myUserActivityListener, myDisposable);
    }
  }

  private void checkIfConfigFileOverridesOptions(final String configFilePath) {
    final InfoFromConfigFile info = FlexCompilerConfigFileUtil.getInfoFromConfigFile(configFilePath);
    overriddenValuesChanged(info.getMainClass(myModule), info.getOutputFileName(), info.getOutputFolderPath());
    myDependenciesConfigurable.overriddenTargetPlayerChanged(info.getTargetPlayer());
  }

  /**
   * Called when {@link CompilerOptionsConfigurable} is initialized and when path to additional config file is changed
   * <code>null</code> parameter value means that the value is not overridden in additional config file
   */
  public void overriddenValuesChanged(final @Nullable String mainClass,
                                      final @Nullable String outputFileName,
                                      final @Nullable String outputFolderPath) {
    myMainClassWarning.setToolTipText(FlexBundle.message("actual.value.from.config.file.0", mainClass));
    myMainClassWarning.setVisible(myMainClassComponent.isVisible() && mainClass != null);

    myOutputFileNameWarning.setToolTipText(FlexBundle.message("actual.value.from.config.file.0", outputFileName));
    myOutputFileNameWarning.setVisible(outputFileName != null);

    myOutputFolderWarning.setToolTipText(
      FlexBundle.message("actual.value.from.config.file.0", FileUtil.toSystemDependentName(StringUtil.notNullize(outputFolderPath))));
    myOutputFolderWarning.setVisible(outputFolderPath != null);

    final String warning = myMainClassWarning.isVisible() && outputFileName == null && outputFolderPath == null
                           ? FlexBundle.message("overridden.in.config.file", "Main class", mainClass)
                           : !myMainClassWarning.isVisible() && outputFileName != null && outputFolderPath != null
                             ? FlexBundle.message("overridden.in.config.file", "Output path",
                                                  FileUtil.toSystemDependentName(outputFolderPath + "/" + outputFileName))
                             : FlexBundle.message("main.class.and.output.overridden.in.config.file");
    myWarning.setText(warning);

    myWarning.setVisible(myMainClassWarning.isVisible() || myOutputFileNameWarning.isVisible() || myOutputFolderWarning.isVisible());
  }

  @Nls
  public String getDisplayName() {
    return myName;
  }

  @Override
  public void updateName() {
    myFreeze = true;
    try {
      myNameField.setText(getDisplayName());
    }
    finally {
      myFreeze = false;
    }
  }

  public void setDisplayName(final String name) {
    myName = name;
  }

  public String getBannerSlogan() {
    return "Build Configuration '" + myName + "'";
  }

  public Module getModule() {
    return myModule;
  }

  public String getModuleName() {
    final ModuleEditor moduleEditor = getModulesConfigurator().getModuleEditor(myModule);
    assert moduleEditor != null : myModule;
    return moduleEditor.getName();
  }

  private ModulesConfigurator getModulesConfigurator() {
    return ModuleStructureConfigurable.getInstance(myModule.getProject()).getContext().getModulesConfigurator();
  }

  public Icon getIcon() {
    return myConfiguration.getIcon();
  }

  public ModifiableFlexIdeBuildConfiguration getEditableObject() {
    return myConfiguration;
  }

  public String getHelpTopic() {
    return "Build_Configuration_page";
  }

  public JComponent createOptionsPanel() {
    return myMainPanel;
  }

  private void updateControls() {
    final TargetPlatform targetPlatform = myConfiguration.getTargetPlatform();
    final OutputType outputType = myConfiguration.getOutputType();

    myOptimizeForPanel.setVisible(false /*outputType == OutputType.RuntimeLoadedModule*/);

    final boolean showMainClass = outputType == OutputType.Application || outputType == OutputType.RuntimeLoadedModule;
    myMainClassLabel.setVisible(showMainClass);
    myMainClassComponent.setVisible(showMainClass);

    myHtmlWrapperPanel.setVisible(targetPlatform == TargetPlatform.Web && outputType == OutputType.Application);
    myWrapperFolderLabel.setEnabled(myUseHTMLWrapperCheckBox.isSelected());
    myWrapperTemplateTextWithBrowse.setEnabled(myUseHTMLWrapperCheckBox.isSelected());
    myCreateHtmlWrapperTemplateButton.setEnabled(myUseHTMLWrapperCheckBox.isSelected());

    final boolean cssFilesVisible = outputType == OutputType.Application && targetPlatform != TargetPlatform.Mobile;
    myCssFilesLabel.setVisible(cssFilesVisible);
    myCssFilesTextWithBrowse.setVisible(cssFilesVisible);
    updateCssFilesText();
  }

  private void updateCssFilesText() {
    final String s = StringUtil.join(myCssFilesToCompile, new Function<String, String>() {
      public String fun(final String path) {
        return PathUtil.getFileName(path);
      }
    }, ", ");
    myCssFilesTextWithBrowse.setText(s);
  }

  public String getTreeNodeText() {
    return myConfiguration.getShortText();
  }

  public OutputType getOutputType() {
    // immutable field
    return myConfiguration.getOutputType();
  }

  public boolean isModified() {
    if (!myConfiguration.getName().equals(myName)) return true;
    if (!myConfiguration.getOptimizeFor().equals(myOptimizeForCombo.getSelectedItem())) return true;
    if (!myConfiguration.getMainClass().equals(myMainClassComponent.getText().trim())) return true;
    if (!myConfiguration.getOutputFileName().equals(myOutputFileNameTextField.getText().trim())) return true;
    if (!myConfiguration.getOutputFolder().equals(FileUtil.toSystemIndependentName(myOutputFolderField.getText().trim()))) return true;
    if (myConfiguration.isUseHtmlWrapper() != myUseHTMLWrapperCheckBox.isSelected()) return true;
    if (!myConfiguration.getWrapperTemplatePath()
      .equals(FileUtil.toSystemIndependentName(myWrapperTemplateTextWithBrowse.getText().trim()))) {
      return true;
    }
    if (!myConfiguration.getCssFilesToCompile().equals(myCssFilesToCompile)) return true;
    if (myConfiguration.isSkipCompile() != mySkipCompilationCheckBox.isSelected()) return true;

    if (myDependenciesConfigurable.isModified()) return true;
    if (myCompilerOptionsConfigurable.isModified()) return true;
    if (myAirDesktopPackagingConfigurable != null && myAirDesktopPackagingConfigurable.isModified()) return true;
    if (myAndroidPackagingConfigurable != null && myAndroidPackagingConfigurable.isModified()) return true;
    if (myIOSPackagingConfigurable != null && myIOSPackagingConfigurable.isModified()) return true;

    return false;
  }

  public void apply() throws ConfigurationException {
    applyOwnTo(myConfiguration, true);

    myDependenciesConfigurable.apply();
    myCompilerOptionsConfigurable.apply();
    if (myAirDesktopPackagingConfigurable != null) myAirDesktopPackagingConfigurable.apply();
    if (myAndroidPackagingConfigurable != null) myAndroidPackagingConfigurable.apply();
    if (myIOSPackagingConfigurable != null) myIOSPackagingConfigurable.apply();
    // main class validation is based on live settings from dependencies tab
    rebuildMainClassFilter();
  }

  private void rebuildMainClassFilter() {
    myMainClassFilter = BCUtils.getMainClassFilter(myModule, myConfiguration, true);
  }

  private void applyOwnTo(ModifiableFlexIdeBuildConfiguration configuration, boolean validate) throws ConfigurationException {
    if (validate && StringUtil.isEmptyOrSpaces(myName)) {
      throw new ConfigurationException("Module '" + getModuleName() + "': build configuration name is empty");
    }
    configuration.setName(myName);
    configuration.setOptimizeFor((String)myOptimizeForCombo.getSelectedItem()); // todo myOptimizeForCombo should contain live information
    configuration.setMainClass(myMainClassComponent.getText().trim());
    configuration.setOutputFileName(myOutputFileNameTextField.getText().trim());
    configuration.setOutputFolder(FileUtil.toSystemIndependentName(myOutputFolderField.getText().trim()));
    configuration.setUseHtmlWrapper(myUseHTMLWrapperCheckBox.isSelected());
    configuration.setWrapperTemplatePath(FileUtil.toSystemIndependentName(myWrapperTemplateTextWithBrowse.getText().trim()));
    configuration.setCssFilesToCompile(myCssFilesToCompile);
    configuration.setSkipCompile(mySkipCompilationCheckBox.isSelected());
  }

  public void reset() {
    myFreeze = true;
    try {
      setDisplayName(myConfiguration.getName());
      myNatureLabel.setText(myConfiguration.getNature().getPresentableText());
      myOptimizeForCombo.setSelectedItem(myConfiguration.getOptimizeFor());

      myMainClassComponent.setText(myConfiguration.getMainClass());
      myOutputFileNameTextField.setText(myConfiguration.getOutputFileName());
      myOutputFolderField.setText(FileUtil.toSystemDependentName(myConfiguration.getOutputFolder()));
      myUseHTMLWrapperCheckBox.setSelected(myConfiguration.isUseHtmlWrapper());
      myWrapperTemplateTextWithBrowse.setText(FileUtil.toSystemDependentName(myConfiguration.getWrapperTemplatePath()));
      myCssFilesToCompile = new ArrayList<String>(myConfiguration.getCssFilesToCompile());
      mySkipCompilationCheckBox.setSelected(myConfiguration.isSkipCompile());

      updateControls();
      overriddenValuesChanged(null, null, null); // no warnings initially

      myDependenciesConfigurable.reset();
      myCompilerOptionsConfigurable.reset();
      if (myAirDesktopPackagingConfigurable != null) myAirDesktopPackagingConfigurable.reset();
      if (myAndroidPackagingConfigurable != null) myAndroidPackagingConfigurable.reset();
      if (myIOSPackagingConfigurable != null) myIOSPackagingConfigurable.reset();
    }
    finally {
      myFreeze = false;
    }
    rebuildMainClassFilter();
    myContext.getDaemonAnalyzer().queueUpdate(myStructureElement);
  }

  public void disposeUIResources() {
    myDependenciesConfigurable.disposeUIResources();
    myCompilerOptionsConfigurable.disposeUIResources();
    if (myAirDesktopPackagingConfigurable != null) myAirDesktopPackagingConfigurable.disposeUIResources();
    if (myAndroidPackagingConfigurable != null) myAndroidPackagingConfigurable.disposeUIResources();
    if (myIOSPackagingConfigurable != null) myIOSPackagingConfigurable.disposeUIResources();
    Disposer.dispose(myDisposable);
  }

  //public ModifiableFlexIdeBuildConfiguration getCurrentConfiguration() {
  //  final ModifiableFlexIdeBuildConfiguration configuration = Factory.createBuildConfiguration();
  //  try {
  //    applyTo(configuration, false);
  //  }
  //  catch (ConfigurationException ignored) {
  //    // no validation
  //  }
  //  return configuration;
  //}

  private List<NamedConfigurable> getChildren() {
    final List<NamedConfigurable> children = new ArrayList<NamedConfigurable>();

    children.add(myDependenciesConfigurable);
    children.add(myCompilerOptionsConfigurable);
    ContainerUtil.addIfNotNull(myAirDesktopPackagingConfigurable, children);
    ContainerUtil.addIfNotNull(myAndroidPackagingConfigurable, children);
    ContainerUtil.addIfNotNull(myIOSPackagingConfigurable, children);

    return children;
  }

  public CompositeConfigurable wrapInTabs() {
    List<NamedConfigurable> tabs = new ArrayList<NamedConfigurable>();
    tabs.add(this);
    tabs.addAll(getChildren());
    return new CompositeConfigurable(tabs, null);
  }

  public void updateTabs(final CompositeConfigurable compositeConfigurable) {
    final List<NamedConfigurable> children = compositeConfigurable.getChildren();
    assert children.get(0) == this : children.get(0).getDisplayName();

    for (int i = children.size() - 1; i >= 1; i--) {
      compositeConfigurable.removeChildAt(i);
    }

    for (NamedConfigurable child : getChildren()) {
      compositeConfigurable.addChild(child);
    }
  }

  public DependenciesConfigurable getDependenciesConfigurable() {
    return myDependenciesConfigurable;
  }

  public boolean isParentFor(final DependenciesConfigurable dependenciesConfigurable) {
    return myDependenciesConfigurable == dependenciesConfigurable;
  }

  private void createUIComponents() {
    myChangeNatureHyperlink = new HoverHyperlinkLabel("Change...");

    rebuildMainClassFilter();
    myMainClassComponent = JSReferenceEditor.forClassName("", myModule.getProject(), null,
                                                          myModule.getModuleScope(false), null,
                                                          Conditions.<JSClass>alwaysTrue(), // no filtering until IDEA-83046
                                                          ExecutionBundle.message("choose.main.class.dialog.title"));
  }

  public void addSharedLibrary(final Library library) {
    myDependenciesConfigurable.addSharedLibraries(Collections.singletonList(library));
  }

  public static FlexIdeBCConfigurable unwrap(CompositeConfigurable c) {
    return (FlexIdeBCConfigurable)c.getMainChild();
  }

  @Override
  public String getTabTitle() {
    return TAB_NAME;
  }

  @Override
  public ProjectStructureElement getProjectStructureElement() {
    return myStructureElement;
  }
}
