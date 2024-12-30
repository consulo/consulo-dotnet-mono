/*
 * Copyright 2013-2015 must-be.org
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

package consulo.dotnet.mono.debugger.proxy;

import consulo.application.AccessRule;
import consulo.application.util.SystemInfo;
import consulo.application.util.concurrent.AppExecutorUtil;
import consulo.dotnet.debugger.DotNetDebuggerUtil;
import consulo.dotnet.debugger.proxy.DotNetThreadProxy;
import consulo.dotnet.debugger.proxy.DotNetTypeProxy;
import consulo.dotnet.debugger.proxy.DotNetVirtualMachineProxy;
import consulo.dotnet.debugger.proxy.value.*;
import consulo.dotnet.module.extension.DotNetModuleLangExtension;
import consulo.dotnet.mono.debugger.TypeMirrorUnloadedException;
import consulo.execution.debug.breakpoint.XBreakpoint;
import consulo.language.util.ModuleUtilCore;
import consulo.logging.Logger;
import consulo.module.Module;
import consulo.module.content.ProjectFileIndex;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.MultiMap;
import consulo.util.collection.SmartList;
import consulo.util.lang.Comparing;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.archive.ArchiveVfsUtil;
import mono.debugger.*;
import mono.debugger.request.EventRequest;
import mono.debugger.request.EventRequestManager;
import mono.debugger.request.StepRequest;
import mono.debugger.request.TypeLoadRequest;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author VISTALL
 * @since 16.04.2015
 */
public class MonoVirtualMachineProxy implements DotNetVirtualMachineProxy
{
	private static class TypeRequestInfo
	{
		private EventRequest myEventRequest;

		private final AtomicInteger myCount = new AtomicInteger(1);

		private TypeRequestInfo(EventRequest eventRequest)
		{
			myEventRequest = eventRequest;
		}
	}

	private static final Logger LOGGER = Logger.getInstance(MonoVirtualMachineProxy.class);

	private final Map<Integer, AppDomainMirror> myLoadedAppDomains = new ConcurrentHashMap<>();
	private final Set<StepRequest> myStepRequests = new LinkedHashSet<>();
	private final MultiMap<XBreakpoint, EventRequest> myBreakpointEventRequests = MultiMap.create();

	private final Map<XBreakpoint<?>, String> myQNameByBreakpoint = new ConcurrentHashMap<>();
	private final Map<String, TypeRequestInfo> myTypeRequests = new ConcurrentHashMap<>();

	private final VirtualMachine myVirtualMachine;

	private final boolean mySupportSearchTypesBySourcePaths;
	private final boolean mySupportSearchTypesByQualifiedName;
	private final boolean mySupportSystemThreadId;
	private final boolean mySupportTypeRequestByName;

	private final ExecutorService myExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("mono vm invoker", 1);

	public MonoVirtualMachineProxy(@Nonnull VirtualMachine virtualMachine)
	{
		myVirtualMachine = virtualMachine;
		mySupportSearchTypesByQualifiedName = myVirtualMachine.isAtLeastVersion(2, 9);
		mySupportTypeRequestByName = myVirtualMachine.isAtLeastVersion(2, 9);
		mySupportSearchTypesBySourcePaths = myVirtualMachine.isAtLeastVersion(2, 7);
		mySupportSystemThreadId = myVirtualMachine.isAtLeastVersion(2, 2);
	}

	@Override
	public void invoke(@Nonnull Runnable runnable)
	{
		myExecutor.execute(() ->
		{
			try
			{
				runnable.run();
			}
			catch(VMDisconnectedException ignored)
			{
			}
			catch(Throwable e)
			{
				LOGGER.error(e);
			}
		});
	}

	@Nullable
	@Override
	public DotNetTypeProxy findTypeInCorlib(@Nonnull String vmQName)
	{
		AssemblyMirror assemblyMirror = myVirtualMachine.rootAppDomain().corlibAssembly();

		TypeMirror typeMirror = assemblyMirror.findTypeByQualifiedName(vmQName, false);
		if(typeMirror == null)
		{
			return null;
		}
		return MonoTypeProxy.of(typeMirror);
	}

	@Nullable
	@Override
	public DotNetTypeProxy findType(@Nonnull Project project, @Nonnull String vmQName, @Nonnull VirtualFile virtualFile)
	{
		try
		{
			TypeMirror typeMirror = findTypeMirror(project, virtualFile, vmQName);
			return MonoTypeProxy.of(typeMirror);
		}
		catch(TypeMirrorUnloadedException ignored)
		{
		}
		return null;
	}

	@Nonnull
	@Override
	public List<DotNetThreadProxy> getThreads()
	{
		return ContainerUtil.map(myVirtualMachine.allThreads(), threadMirror -> new MonoThreadProxy(MonoVirtualMachineProxy.this, threadMirror));
	}

	@Nonnull
	@Override
	public DotNetStringValueProxy createStringValue(@Nonnull String value)
	{
		return MonoValueProxyUtil.wrap(myVirtualMachine.rootAppDomain().createString(value));
	}

	@Nonnull
	@Override
	public DotNetCharValueProxy createCharValue(char value)
	{
		return MonoValueProxyUtil.wrap(new CharValueMirror(myVirtualMachine, value));
	}

	@Nonnull
	@Override
	public DotNetBooleanValueProxy createBooleanValue(boolean value)
	{
		return MonoValueProxyUtil.wrap(new BooleanValueMirror(myVirtualMachine, value));
	}

	@Nonnull
	@Override
	public DotNetNumberValueProxy createNumberValue(int tag, @Nonnull Number value)
	{
		return MonoValueProxyUtil.wrap(new NumberValueMirror(myVirtualMachine, tag, value));
	}

	@Nonnull
	@Override
	public DotNetNullValueProxy createNullValue()
	{
		return MonoValueProxyUtil.wrap(new NoObjectValueMirror(myVirtualMachine));
	}

	public boolean isSupportSystemThreadId()
	{
		return mySupportSystemThreadId;
	}

	public boolean isSupportTypeRequestByName()
	{
		return mySupportTypeRequestByName;
	}

	public void dispose()
	{
		myExecutor.shutdown();
		myStepRequests.clear();
		myVirtualMachine.dispose();
		myBreakpointEventRequests.clear();
	}

	public void addStepRequest(@Nonnull StepRequest stepRequest)
	{
		myStepRequests.add(stepRequest);
	}

	public void stopStepRequest(@Nonnull StepRequest stepRequest)
	{
		stepRequest.disable();
		myStepRequests.remove(stepRequest);
	}

	public void enableTypeRequest(@Nonnull XBreakpoint<?> breakpoint, @Nullable String qName)
	{
		if(!mySupportTypeRequestByName || qName == null)
		{
			return;
		}

		myQNameByBreakpoint.put(breakpoint, qName);

		TypeRequestInfo info = myTypeRequests.get(qName);
		if(info != null)
		{
			info.myCount.incrementAndGet();
		}
		else
		{
			TypeLoadRequest request = eventRequestManager().createTypeLoadRequest();
			request.addTypeNameFilter(qName);

			myTypeRequests.put(qName, new TypeRequestInfo(request));

			request.enable();
		}
	}

	public void disableTypeRequest(@Nonnull XBreakpoint<?> breakpoint)
	{
		String qName = myQNameByBreakpoint.remove(breakpoint);
		if(qName == null)
		{
			return;
		}

		TypeRequestInfo info = myTypeRequests.get(qName);
		if(info == null)
		{
			return;
		}

		int count = info.myCount.decrementAndGet();
		if(count <= 0)
		{
			myTypeRequests.remove(qName);

			info.myEventRequest.delete();
		}
	}

	public void putRequest(@Nonnull XBreakpoint<?> breakpoint, @Nonnull EventRequest request)
	{
		myBreakpointEventRequests.putValue(breakpoint, request);
	}

	@Nullable
	public XBreakpoint<?> findBreakpointByRequest(@Nonnull EventRequest eventRequest)
	{
		for(Map.Entry<XBreakpoint, Collection<EventRequest>> entry : myBreakpointEventRequests.entrySet())
		{
			if(entry.getValue().contains(eventRequest))
			{
				return entry.getKey();
			}
		}
		return null;
	}

	public void disposeAllRelatedDataForBreakpoint(@Nonnull XBreakpoint<?> breakpoint, boolean removeTypeRequest)
	{
		// remove type request on breakpoint remove
		if(removeTypeRequest)
		{
			disableTypeRequest(breakpoint);
		}

		Collection<EventRequest> eventRequests = myBreakpointEventRequests.remove(breakpoint);
		if(eventRequests == null)
		{
			return;
		}
		for(EventRequest eventRequest : eventRequests)
		{
			eventRequest.disable();
		}
		myVirtualMachine.eventRequestManager().deleteEventRequests(eventRequests);
	}

	public void stopStepRequests()
	{
		for(StepRequest stepRequest : myStepRequests)
		{
			stepRequest.disable();
		}
		myStepRequests.clear();
	}

	public EventRequestManager eventRequestManager()
	{
		return myVirtualMachine.eventRequestManager();
	}

	@Nonnull
	public VirtualMachine getDelegate()
	{
		return myVirtualMachine;
	}

	@Nullable
	public TypeMirror findTypeMirror(@Nonnull Project project, @Nonnull final VirtualFile virtualFile, @Nonnull final String vmQualifiedName) throws TypeMirrorUnloadedException
	{
		List<TypeMirror> typeMirrors = findTypeMirrors(project, virtualFile, vmQualifiedName);
		return ContainerUtil.getFirstItem(typeMirrors);
	}

	@Nonnull
	private List<TypeMirror> findTypeMirrors(@Nonnull Project project, @Nonnull final VirtualFile virtualFile, @Nonnull final String vmQualifiedName) throws TypeMirrorUnloadedException
	{
		try
		{
			List<TypeMirror> list = new SmartList<>();
			if(mySupportSearchTypesByQualifiedName)
			{
				TypeMirror[] typesByQualifiedName = myVirtualMachine.findTypesByQualifiedName(vmQualifiedName, false);
				Collections.addAll(list, typesByQualifiedName);
			}

			if(mySupportSearchTypesBySourcePaths)
			{
				TypeMirror[] typesBySourcePath = myVirtualMachine.findTypesBySourcePath(virtualFile.getPath(), SystemInfo.isFileSystemCaseSensitive);
				for(TypeMirror typeMirror : typesBySourcePath)
				{
					if(Comparing.equal(DotNetDebuggerUtil.getVmQName(typeMirror.fullName()), vmQualifiedName))
					{
						list.add(typeMirror);
					}
				}
			}

			String assemblyTitle = null;
			if(ProjectFileIndex.getInstance(project).isInLibraryClasses(virtualFile))
			{
				VirtualFile archiveRoot = ArchiveVfsUtil.getVirtualFileForArchive(virtualFile);
				if(archiveRoot != null)
				{
					assemblyTitle = archiveRoot.getNameWithoutExtension();
				}
			}
			else
			{
				Module moduleForFile = ModuleUtilCore.findModuleForFile(virtualFile, project);
				if(moduleForFile == null)
				{
					return list;
				}

				final DotNetModuleLangExtension<?> extension = ModuleUtilCore.getExtension(moduleForFile, DotNetModuleLangExtension.class);
				if(extension == null)
				{
					return list;
				}

				assemblyTitle = getAssemblyTitle(extension);
			}

			for(AppDomainMirror appDomainMirror : myLoadedAppDomains.values())
			{
				AssemblyMirror[] assemblies = appDomainMirror.assemblies();
				for(AssemblyMirror assembly : assemblies)
				{
					String assemblyName = getAssemblyName(assembly.name());
					if(Comparing.equal(assemblyTitle, assemblyName))
					{
						TypeMirror typeByQualifiedName = assembly.findTypeByQualifiedName(vmQualifiedName, false);
						if(typeByQualifiedName != null)
						{
							list.add(typeByQualifiedName);
						}
					}
				}
			}
			return list;
		}
		catch(VMDisconnectedException e)
		{
			return Collections.emptyList();
		}
	}

	@Nonnull
	private static String getAssemblyTitle(@Nonnull DotNetModuleLangExtension<?> extension)
	{
		return AccessRule.read(() ->
		{
			String assemblyTitle = extension.getAssemblyTitle();
			if(assemblyTitle != null)
			{
				return assemblyTitle;
			}
			return extension.getModule().getName();
		});
	}

	@Nonnull
	private static String getAssemblyName(String name)
	{
		int i = name.indexOf(',');
		if(i == -1)
		{
			return name;
		}
		return name.substring(0, i);
	}

	public void resume()
	{
		try
		{
			myVirtualMachine.resume();
		}
		catch(NotSuspendedException ignored)
		{
		}
	}

	public void suspend()
	{
		myVirtualMachine.suspend();
	}

	@Nonnull
	public List<ThreadMirror> allThreads()
	{
		return myVirtualMachine.allThreads();
	}

	public void loadAppDomain(AppDomainMirror appDomainMirror)
	{
		myLoadedAppDomains.put(appDomainMirror.id(), appDomainMirror);
	}

	public void unloadAppDomain(AppDomainMirror appDomainMirror)
	{
		myLoadedAppDomains.remove(appDomainMirror.id());
	}
}
