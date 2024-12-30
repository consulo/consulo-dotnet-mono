/*
 * Copyright 2013-2016 consulo.io
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

package consulo.dotnet.mono.debugger.run;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.AllIcons;
import consulo.dotnet.debugger.impl.DotNetDebugProcessBase;
import consulo.dotnet.debugger.impl.runner.remote.DotNetRemoteConfiguration;
import consulo.dotnet.module.extension.DotNetModuleExtension;
import consulo.dotnet.mono.debugger.MonoDebugProcess;
import consulo.dotnet.mono.debugger.MonoVirtualMachineListener;
import consulo.dotnet.mono.debugger.localize.MonoDebuggerLocalize;
import consulo.dotnet.util.DebugConnectionInfo;
import consulo.execution.configuration.ConfigurationFactory;
import consulo.execution.configuration.ConfigurationTypeBase;
import consulo.execution.configuration.RunConfiguration;
import consulo.execution.debug.XDebugSession;
import consulo.module.extension.ModuleExtensionHelper;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandler;
import consulo.process.ProcessHandlerStopper;
import consulo.process.ProcessOutputTypes;
import consulo.project.Project;
import mono.debugger.VirtualMachine;

import jakarta.annotation.Nonnull;

/**
 * @author VISTALL
 * @since 27-Dec-16
 */
@ExtensionImpl
public class MonoRemoteConfiguration extends ConfigurationTypeBase
{
	public MonoRemoteConfiguration()
	{
		super("MonoRemoteConfiguration", MonoDebuggerLocalize.monoRemoteConfigurationName(), AllIcons.RunConfigurations.Remote);

		addFactory(new ConfigurationFactory(this)
		{
			@Override
			public RunConfiguration createTemplateConfiguration(Project project)
			{
				return new DotNetRemoteConfiguration(project, this)
				{
					@Nonnull
					@Override
					public DotNetDebugProcessBase createDebuggerProcess(@Nonnull XDebugSession session, @Nonnull DebugConnectionInfo info) throws ExecutionException
					{
						MonoDebugProcess process = new MonoDebugProcess(session, this, info);
						process.getDebugThread().addListener(new MonoVirtualMachineListener()
						{
							@Override
							public void connectionSuccess(@Nonnull VirtualMachine machine)
							{
								ProcessHandler processHandler = process.getProcessHandler();
								processHandler.notifyTextAvailable(String.format("Success attach to %s:%d", info.getHost(), info.getPort()), ProcessOutputTypes.STDOUT);
							}

							@Override
							public void connectionStopped()
							{
							}

							@Override
							public void connectionFailed()
							{
								ProcessHandler processHandler = process.getProcessHandler();
								processHandler.notifyTextAvailable(String.format("Failed attach to %s:%d", info.getHost(), info.getPort()), ProcessOutputTypes.STDERR);
								ProcessHandlerStopper.stop(processHandler);
							}
						});
						return process;
					}
				};
			}

			@Override
			public boolean isApplicable(@Nonnull Project project)
			{
				return ModuleExtensionHelper.getInstance(project).hasModuleExtension(DotNetModuleExtension.class);
			}
		});
	}
}
