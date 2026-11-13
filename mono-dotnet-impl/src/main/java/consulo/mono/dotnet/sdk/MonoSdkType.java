/*
 * Copyright 2013 must-be.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package consulo.mono.dotnet.sdk;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.Application;
import consulo.application.util.SystemInfo;
import consulo.content.bundle.*;
import consulo.dataContext.DataContext;
import consulo.dataContext.DataManager;
import consulo.dotnet.sdk.DotNetSdkType;
import consulo.fileChooser.FileChooser;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.localize.LocalizeValue;
import consulo.mono.dotnet.icon.MonoDotNetIconGroup;
import consulo.platform.Platform;
import consulo.platform.PlatformFileSystem;
import consulo.platform.PlatformOperatingSystem;
import consulo.ui.Component;
import consulo.ui.MessageBoxes;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * @author VISTALL
 * @since 06.12.13.
 */
@ExtensionImpl
public class MonoSdkType extends DotNetSdkType implements SdkTypeWithCustomCreateUI {
    public static final String ourDefaultLinuxCompilerPath = "/usr/bin/mcs";
    public static final String ourDefaultFreeBSDCompilerPath = "/usr/local/bin/mcs";

    private static final String apiVer = "-api";

    private static final String[] ourMonoPaths = new String[]{
        "C:/Program Files/Mono/",
        "C:/Program Files (x86)/Mono/"
    };

    /**
     * The mono of the machine of the bundle.
     */
    @Nonnull
    public String getExecutable(@Nonnull Sdk sdk) {
        Platform platform = sdk.getPlatform();
        PlatformOperatingSystem os = platform.os();
        String runFile;
        if (os.isWindows()) {
            runFile = sdk.getHomePath() + "/../../../bin/mono.exe";
        }
        else if (os.isMac()) {
            runFile = sdk.getHomePath() + "/../../../bin/mono";
        }
        else if (os.isFreeBSD()) {
            runFile = "/usr/local/bin/mono";
        }
        else if (os.isLinux()) {
            runFile = "/usr/bin/mono";
        }
        else {
            throw new UnsupportedOperationException(platform.getName());
        }
        return runFile;
    }

    @Nonnull
    public static MonoSdkType getInstance() {
        return Application.get().getExtensionPoint(SdkType.class).findExtensionOrFail(MonoSdkType.class);
    }

    public MonoSdkType() {
        super("MONO_DOTNET_SDK", LocalizeValue.localizeTODO("Mono"), MonoDotNetIconGroup.mono());
    }

    @Override
    public void collectHomePaths(@Nonnull Platform platform, @Nonnull Consumer<Path> consumer) {
        Path defaultHomePath = getDefaultHomePath(platform);
        if (defaultHomePath == null) {
            return;
        }
        try (Stream<Path> children = Files.list(defaultHomePath.resolve("lib/mono"))) {
            children.forEach(consumer);
        }
        catch (IOException ignored) {
        }
    }

    @Override
    public boolean canCreatePredefinedSdks(@Nonnull Platform platform) {
        return true;
    }

    @Nullable
    private Path getDefaultHomePath(@Nonnull Platform platform) {
        PlatformOperatingSystem os = platform.os();
        PlatformFileSystem fs = platform.fs();
        if (os.isWindows()) {
            for (String monoPath : ourMonoPaths) {
                Path path = fs.getPath(monoPath);
                if (Files.exists(path)) {
                    return path;
                }
            }
            return fs.getPath(ourMonoPaths[0]);
        }

        if (os.isMac()) {
            return fs.getPath("/Library/Frameworks/Mono.framework/Home/");
        }

        if (os.isFreeBSD()) {
            if (Files.exists(fs.getPath(ourDefaultFreeBSDCompilerPath))) {
                return fs.getPath("/usr/local/");
            }
        }

        if (os.isLinux()) {
            if (Files.exists(fs.getPath(ourDefaultLinuxCompilerPath))) {
                return fs.getPath("/usr/");
            }
        }
        return null;
    }

    @Override
    public boolean isValidSdkHome(@Nonnull Platform platform, @Nonnull Path path) {
        return Files.exists(path.resolve("mscorlib.dll"));
    }

    @Nullable
    @Override
    public String getVersionString(@Nonnull Platform platform, @Nonnull Path path) {
        String directoryName = path.getFileName().toString();
        if (directoryName.endsWith(apiVer)) {
            return directoryName.substring(0, directoryName.length() - apiVer.length());
        }
        return directoryName;
    }

    @Nonnull
    @Override
    public String suggestSdkName(@Nonnull Platform platform, @Nullable String currentSdkName, @Nonnull Path sdkHome) {
        return getDisplayName().get() + " " + sdkHome.getFileName();
    }

    @Override
    @RequiredUIAccess
    public void showCustomCreateUI(SdkModel sdkModel, Component parentComponent, Consumer<Sdk> sdkCreatedCallback) {
        if (SystemInfo.isFreeBSD) {
            File file = new File(ourDefaultFreeBSDCompilerPath);
            if (!file.exists()) {
                showError(parentComponent, "\'" + ourDefaultFreeBSDCompilerPath + "\' not found. OS: " + SystemInfo.OS_NAME);
                return;
            }
            showSdkChooser(parentComponent, new File("/usr/local/lib/mono"), sdkCreatedCallback);
        }
        else if (SystemInfo.isLinux) {
            File file = new File(ourDefaultLinuxCompilerPath);
            if (!file.exists()) {
                showError(parentComponent, "\'" + ourDefaultLinuxCompilerPath + "\' not found. OS: " + SystemInfo.OS_NAME);
                return;
            }
            showSdkChooser(parentComponent, new File("/usr/lib/mono"), sdkCreatedCallback);
        }
        else if (SystemInfo.isWindows || SystemInfo.isMac) {
            FileChooserDescriptor singleFolderDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
            Path toSelectPath = getDefaultHomePath(Platform.current());
            VirtualFile toSelect = toSelectPath == null ? null : LocalFileSystem.getInstance().findFileByNioFile(toSelectPath);

            UIAccess uiAccess = UIAccess.current();
            FileChooser.chooseFile(singleFolderDescriptor, null, toSelect).whenComplete((monoDir, error) -> {
                if (error != null || monoDir == null) {
                    return;
                }

                uiAccess.give(() -> showSdkChooser(parentComponent, new File(monoDir.getPath(), "lib/mono"), sdkCreatedCallback));
            });
        }
        else {
            showError(parentComponent, "Current OS is not supported: " + SystemInfo.OS_NAME);
        }
    }

    @RequiredUIAccess
    private void showSdkChooser(Component parentComponent, File monoLib, Consumer<Sdk> sdkCreatedCallback) {
        if (!monoLib.exists()) {
            showError(parentComponent, "File: " + monoLib.getAbsolutePath() + " is not exists.");
            return;
        }

        List<Pair<String, File>> list = new ArrayList<>();

        File[] files = monoLib.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isValidSdkHome(Platform.current(), file.toPath())) {
                    list.add(Pair.create(file.getName(), file));
                }
            }
        }

        ActionGroup.Builder b = ActionGroup.newImmutableBuilder();
        for (Pair<String, File> pair : list) {
            b.add(new AnAction(pair.getFirst()) {
                @RequiredUIAccess
                @Override
                public void actionPerformed(@Nonnull AnActionEvent anActionEvent) {
                    File path = pair.getSecond();
                    String absolutePath = path.getAbsolutePath();

                    String uniqueSdkName = SdkUtil.createUniqueSdkName(MonoSdkType.this, absolutePath, SdkTable.getInstance().getAllSdks());
                    Sdk sdk = SdkTable.getInstance().createSdk(uniqueSdkName, MonoSdkType.this);
                    SdkModificator modificator = sdk.getSdkModificator();
                    modificator.setVersionString(getVersionString(Platform.current(), path.toPath()));
                    modificator.setHomePath(absolutePath);
                    modificator.commitChanges();

                    sdkCreatedCallback.accept(sdk);
                }
            });
        }

        DataContext dataContext = DataManager.getInstance().getDataContext(parentComponent);

        ListPopup choose = JBPopupFactory.getInstance()
            .createActionGroupPopup("Choose", b.build(), dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);

        choose.showBy(parentComponent, null);
    }

    @RequiredUIAccess
    private static void showError(Component parentComponent, String message) {
        MessageBoxes.okError(LocalizeValue.localizeTODO(message)).showAsync(parentComponent);
    }
}
