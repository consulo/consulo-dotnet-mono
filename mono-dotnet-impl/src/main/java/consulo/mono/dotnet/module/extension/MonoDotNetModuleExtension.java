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

package consulo.mono.dotnet.module.extension;

import consulo.content.OrderRootType;
import consulo.content.base.DocumentationOrderRootType;
import consulo.content.bundle.Sdk;
import consulo.content.bundle.SdkType;
import consulo.dotnet.compiler.DotNetMacroUtil;
import consulo.dotnet.debugger.impl.DotNetDebugProcessBase;
import consulo.dotnet.debugger.impl.DotNetModuleExtensionWithDebug;
import consulo.dotnet.module.extension.BaseDotNetModuleExtension;
import consulo.dotnet.mono.debugger.MonoDebugProcess;
import consulo.dotnet.util.DebugConnectionInfo;
import consulo.execution.configuration.RunProfile;
import consulo.execution.debug.XDebugSession;
import consulo.module.content.layer.ModuleRootLayer;
import consulo.mono.dotnet.sdk.MonoSdkType;
import consulo.process.ExecutionException;
import consulo.process.cmd.GeneralCommandLine;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.util.lang.Comparing;
import consulo.virtualFileSystem.VirtualFile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author VISTALL
 * @since 20.11.13.
 */
public class MonoDotNetModuleExtension extends BaseDotNetModuleExtension<MonoDotNetModuleExtension> implements DotNetModuleExtensionWithDebug
{
	public MonoDotNetModuleExtension(@Nonnull String id, @Nonnull ModuleRootLayer rootModel)
	{
		super(id, rootModel);
	}

	@Nonnull
	@Override
	public Class<? extends SdkType> getSdkTypeClass()
	{
		return MonoSdkType.class;
	}

	@Nonnull
	@Override
	public DotNetDebugProcessBase createDebuggerProcess(@Nonnull XDebugSession session, @Nonnull RunProfile runProfile, @Nonnull DebugConnectionInfo debugConnectionInfo)
	{
		return new MonoDebugProcess(session, runProfile, debugConnectionInfo);
	}

	@Nonnull
	@Override
	public GeneralCommandLine createDefaultCommandLine(@Nonnull Sdk sdk, @Nullable DebugConnectionInfo debugConnectionInfo) throws ExecutionException
	{
		String fileName = DotNetMacroUtil.expandOutputFile(this);
		return createDefaultCommandLineImpl(sdk, debugConnectionInfo, fileName);
	}

	@Nonnull
	public static GeneralCommandLine createDefaultCommandLineImpl(@Nonnull Sdk sdk,
			@Nullable DebugConnectionInfo debugConnectionInfo,
			@Nonnull String fileName)
	{
		GeneralCommandLine commandLine = new GeneralCommandLine();

		String runFile = MonoSdkType.getInstance().getExecutable(sdk);

		commandLine.setExePath(runFile);
		if(debugConnectionInfo != null)
		{
			commandLine.addParameter("--debug");
			commandLine.addParameter(generateParameterForRun(debugConnectionInfo));
		}
		commandLine.addParameter(fileName);
		return commandLine;
	}

	@Nonnull
	@Override
	public String getDebugFileExtension()
	{
		return getTarget().getExtension() + ".mdb";
	}

	@Nonnull
	@Override
	public String[] getSystemLibraryUrlsImpl(@Nonnull Sdk sdk, @Nonnull String name, @Nonnull OrderRootType orderRootType)
	{
		if(orderRootType == DocumentationOrderRootType.getInstance())
		{
			String[] systemLibraryUrls = super.getSystemLibraryUrlsImpl(sdk, name, orderRootType);

			VirtualFile homeDirectory = sdk.getHomeDirectory();
			if(homeDirectory == null)
			{
				return systemLibraryUrls;
			}
			VirtualFile docDir = homeDirectory.findFileByRelativePath("/../../monodoc/sources");
			if(docDir == null)
			{
				return systemLibraryUrls;
			}

			List<String> list = new ArrayList<>();
			ContainerUtil.addAll(list, systemLibraryUrls);

			for(VirtualFile virtualFile : docDir.getChildren())
			{
				if(Comparing.equal(virtualFile.getExtension(), "source"))
				{
					list.add(virtualFile.getUrl());
				}
			}
			return ArrayUtil.toStringArray(list);
		}
		return super.getSystemLibraryUrlsImpl(sdk, name, orderRootType);
	}

	@Nonnull
	@Override
	public File[] getFilesForLibraries()
	{
		File[] filesForLibraries = super.getFilesForLibraries();

		Sdk sdk = getSdk();
		if(sdk != null)
		{
			File facadesDir = new File(sdk.getHomePath(), "Facades");
			if(facadesDir.exists())
			{
				File[] files = facadesDir.listFiles();
				filesForLibraries = ArrayUtil.mergeArrays(filesForLibraries, files);
			}
		}
		return filesForLibraries;
	}

	private static String generateParameterForRun(@Nonnull DebugConnectionInfo debugConnectionInfo)
	{
		StringBuilder builder = new StringBuilder("--debugger-agent=transport=dt_socket,address=");
		builder.append(debugConnectionInfo.getHost());
		builder.append(":");
		builder.append(debugConnectionInfo.getPort());
		if(debugConnectionInfo.isServer())
		{
			builder.append(",suspend=y,server=y");
		}
		else
		{
			builder.append(",suspend=y,server=n");
		}
		return builder.toString();
	}
}
