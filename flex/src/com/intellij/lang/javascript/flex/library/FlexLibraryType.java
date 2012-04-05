package com.intellij.lang.javascript.flex.library;

import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.libraries.LibraryKind;
import com.intellij.openapi.roots.libraries.LibraryType;
import com.intellij.openapi.roots.libraries.LibraryTypeService;
import com.intellij.openapi.roots.libraries.NewLibraryConfiguration;
import com.intellij.openapi.roots.libraries.ui.LibraryEditorComponent;
import com.intellij.openapi.roots.libraries.ui.LibraryPropertiesEditor;
import com.intellij.openapi.roots.libraries.ui.LibraryRootsComponentDescriptor;
import com.intellij.openapi.roots.ui.configuration.FacetsProvider;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * @author nik
 */
public class FlexLibraryType extends LibraryType<FlexLibraryProperties> {
  public static final LibraryKind<FlexLibraryProperties> FLEX_LIBRARY = LibraryKind.create("flex");

  public FlexLibraryType() {
    super(FLEX_LIBRARY);
  }

  @NotNull
  @Override
  public String getCreateActionName() {
    return "ActionScript/Flex";
  }

  @Override
  public NewLibraryConfiguration createNewLibrary(@NotNull JComponent parentComponent,
                                                  @Nullable VirtualFile contextDirectory, @NotNull final Project project) {
    return LibraryTypeService.getInstance().createLibraryFromFiles(createLibraryRootsComponentDescriptor(), parentComponent, contextDirectory, this,
                                                                   project);
  }

  @NotNull
  @Override
  public FlexLibraryProperties createDefaultProperties() {
    return new FlexLibraryProperties();
  }

  @Override
  public boolean isSuitableModule(@NotNull Module module, @NotNull FacetsProvider facetsProvider) {
    return ModuleType.get(module).equals(FlexModuleType.getInstance());
  }

  public LibraryPropertiesEditor createPropertiesEditor(@NotNull final LibraryEditorComponent<FlexLibraryProperties> properties) {
    return null;
  }

  @Override
  public Icon getIcon() {
    return PlatformIcons.LIBRARY_ICON;    // TODO: change icon to Flex specific only when automatic library converters are done
  }

  @NotNull
  public LibraryRootsComponentDescriptor createLibraryRootsComponentDescriptor() {
    return new FlexLibraryRootsComponentDescriptor();
  }

  public static FlexLibraryType getInstance() {
    return LibraryType.EP_NAME.findExtension(FlexLibraryType.class);
  }
}
