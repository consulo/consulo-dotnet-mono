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
import consulo.application.util.SystemInfo;
import consulo.content.bundle.*;
import consulo.dataContext.DataContext;
import consulo.dataContext.DataManager;
import consulo.dotnet.sdk.DotNetSdkType;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.IdeaFileChooser;
import consulo.mono.dotnet.icon.MonoDotNetIconGroup;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionGroup;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.popup.JBPopupFactory;
import consulo.ui.ex.popup.ListPopup;
import consulo.ui.image.Image;
import consulo.util.lang.Pair;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * @author VISTALL
 * @since 06.12.13.
 */
@ExtensionImpl
public class MonoSdkType extends DotNetSdkType implements SdkTypeWithCustomCreateUI
{
	public static final String ourDefaultLinuxCompilerPath = "/usr/bin/mcs";
	public static final String ourDefaultFreeBSDCompilerPath = "/usr/local/bin/mcs";

	private static final String apiVer = "-api";

	private static final String[] ourMonoPaths = new String[]{
			"C:/Program Files/Mono/",
			"C:/Program Files (x86)/Mono/"
	};

	@Nonnull
	public String getExecutable(@Nonnull Sdk sdk)
	{
		String runFile;
		if(SystemInfo.isWindows)
		{
			runFile = sdk.getHomePath() + "/../../../bin/mono.exe";
		}
		else if(SystemInfo.isMac)
		{
			runFile = sdk.getHomePath() + "/../../../bin/mono";
		}
		else if(SystemInfo.isFreeBSD)
		{
			runFile = "/usr/local/bin/mono";
		}
		else if(SystemInfo.isLinux)
		{
			runFile = "/usr/bin/mono";
		}
		else
		{
			throw new UnsupportedOperationException(SystemInfo.OS_NAME);
		}
		return runFile;
	}

	@Nonnull
	public static MonoSdkType getInstance()
	{
		return EP_NAME.findExtensionOrFail(MonoSdkType.class);
	}

	public MonoSdkType()
	{
		super("MONO_DOTNET_SDK");
	}

	@Nonnull
	@Override
	public Collection<String> suggestHomePaths()
	{
		String defaultHomePath = getDefaultHomePath();
		if(defaultHomePath == null)
		{
			return Collections.emptyList();
		}
		File dir = new File(defaultHomePath, "lib/mono");
		if(!dir.exists())
		{
			return Collections.emptyList();
		}
		List<String> list = new ArrayList<>(1);
		for(File file : dir.listFiles())
		{
			list.add(file.getPath());
		}
		return list;
	}

	@Override
	public boolean canCreatePredefinedSdks()
	{
		return true;
	}

	@Nullable
	private String getDefaultHomePath()
	{
		if(SystemInfo.isWindows)
		{
			for(String monoPath : ourMonoPaths)
			{
				File file = new File(monoPath);
				if(file.exists())
				{
					return monoPath;
				}
			}
			return ourMonoPaths[0];
		}

		if(SystemInfo.isMac)
		{
			return "/Library/Frameworks/Mono.framework/Home/";
		}

		if(SystemInfo.isFreeBSD)
		{
			File file = new File(ourDefaultFreeBSDCompilerPath);
			if(file.exists())
			{
				return "/usr/local/";
			}
		}

		if(SystemInfo.isLinux)
		{
			File file = new File(ourDefaultLinuxCompilerPath);
			if(file.exists())
			{
				return "/usr/";
			}
		}
		return null;
	}

	@Override
	public boolean isValidSdkHome(String s)
	{
		return new File(s, "mscorlib.dll").exists();
	}

	@Nullable
	@Override
	public String getVersionString(String path)
	{
		String directoryName = new File(path).getName();
		if(directoryName.endsWith(apiVer))
		{
			return directoryName.substring(0, directoryName.length() - apiVer.length());
		}
		return directoryName;
	}

	@Override
	public String suggestSdkName(String currentSdkName, String sdkHome)
	{
		File file = new File(sdkHome);
		return getPresentableName() + " " + file.getName();
	}

	@Nonnull
	@Override
	public String getPresentableName()
	{
		return "Mono";
	}

	@Nullable
	@Override
	public Image getIcon()
	{
		return MonoDotNetIconGroup.mono();
	}

	@Override
	public void showCustomCreateUI(SdkModel sdkModel, JComponent parentComponent, final Consumer<Sdk> sdkCreatedCallback)
	{
		File monoLib = null;
		if(SystemInfo.isFreeBSD)
		{
			File file = new File(ourDefaultFreeBSDCompilerPath);
			if(!file.exists())
			{
				Messages.showErrorDialog(parentComponent, "\'" + ourDefaultFreeBSDCompilerPath + "\' not found. OS: " + SystemInfo.OS_NAME);
				return;
			}
			monoLib = new File("/usr/local/lib/mono");
		}
		else if(SystemInfo.isLinux)
		{
			File file = new File(ourDefaultLinuxCompilerPath);
			if(!file.exists())
			{
				Messages.showErrorDialog(parentComponent, "\'" + ourDefaultLinuxCompilerPath + "\' not found. OS: " + SystemInfo.OS_NAME);
				return;
			}
			monoLib = new File("/usr/lib/mono");
		}
		else if(SystemInfo.isWindows || SystemInfo.isMac)
		{
			FileChooserDescriptor singleFolderDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
			String toSelectPath = getDefaultHomePath();
			VirtualFile toSelect = toSelectPath == null ? null : LocalFileSystem.getInstance().findFileByPath(toSelectPath);
			VirtualFile monoDir = IdeaFileChooser.chooseFile(singleFolderDescriptor, null, toSelect);
			if(monoDir == null)
			{
				return;
			}

			monoLib = new File(monoDir.getPath(), "lib/mono");
		}

		if(monoLib == null)
		{
			Messages.showErrorDialog(parentComponent, "Current OS is not supported: " + SystemInfo.OS_NAME);
			return;
		}

		if(!monoLib.exists())
		{
			Messages.showErrorDialog(parentComponent, "File: " + monoLib.getAbsolutePath() + " is not exists.");
			return;
		}

		List<Pair<String, File>> list = new ArrayList<Pair<String, File>>();

		File[] files = monoLib.listFiles();
		if(files != null)
		{
			for(File file : files)
			{
				if(isValidSdkHome(file.getAbsolutePath()))
				{
					list.add(Pair.create(file.getName(), file));
				}
			}
		}

		ActionGroup.Builder b = ActionGroup.newImmutableBuilder();
		for(final Pair<String, File> pair : list)
		{
			b.add(new AnAction(pair.getFirst())
			{
				@RequiredUIAccess
				@Override
				public void actionPerformed(@Nonnull AnActionEvent anActionEvent)
				{
					File path = pair.getSecond();
					String absolutePath = path.getAbsolutePath();

					String uniqueSdkName = SdkUtil.createUniqueSdkName(MonoSdkType.this, absolutePath, SdkTable.getInstance().getAllSdks());
					Sdk sdk = SdkTable.getInstance().createSdk(uniqueSdkName, MonoSdkType.this);
					SdkModificator modificator = sdk.getSdkModificator();
					modificator.setVersionString(getVersionString(absolutePath));
					modificator.setHomePath(absolutePath);
					modificator.commitChanges();

					sdkCreatedCallback.accept(sdk);
				}
			});
		}

		DataContext dataContext = DataManager.getInstance().getDataContext(parentComponent);

		ListPopup choose = JBPopupFactory.getInstance().createActionGroupPopup("Choose", b.build(), dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);

		choose.showInCenterOf(parentComponent);
	}
}
