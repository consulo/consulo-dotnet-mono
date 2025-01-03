/*
 * Copyright 2013-2016 must-be.org
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

package consulo.dotnet.mono.debugger.breakpoint;

import consulo.annotation.access.RequiredReadAction;
import consulo.application.AccessRule;
import consulo.dotnet.debugger.DotNetDebuggerSourceLineResolver;
import consulo.dotnet.debugger.DotNetDebuggerUtil;
import consulo.dotnet.debugger.impl.breakpoint.DotNetBreakpointUtil;
import consulo.dotnet.debugger.impl.breakpoint.DotNetExceptionBreakpointType;
import consulo.dotnet.debugger.impl.breakpoint.DotNetLineBreakpointType;
import consulo.dotnet.debugger.impl.breakpoint.properties.DotNetExceptionBreakpointProperties;
import consulo.dotnet.debugger.impl.breakpoint.properties.DotNetLineBreakpointProperties;
import consulo.dotnet.debugger.impl.breakpoint.properties.DotNetMethodBreakpointProperties;
import consulo.dotnet.debugger.impl.nodes.DotNetDebuggerCompilerGenerateUtil;
import consulo.dotnet.mono.debugger.TypeMirrorUnloadedException;
import consulo.dotnet.mono.debugger.proxy.MonoMethodProxy;
import consulo.dotnet.mono.debugger.proxy.MonoTypeProxy;
import consulo.dotnet.mono.debugger.proxy.MonoVirtualMachineProxy;
import consulo.dotnet.util.ArrayUtil2;
import consulo.execution.debug.XDebugSession;
import consulo.execution.debug.breakpoint.XBreakpoint;
import consulo.execution.debug.breakpoint.XLineBreakpoint;
import consulo.language.psi.PsiElement;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.language.psi.util.PsiTreeUtil;
import consulo.project.Project;
import consulo.util.collection.ContainerUtil;
import consulo.util.collection.primitive.ints.IntSet;
import consulo.util.collection.primitive.ints.IntSets;
import consulo.util.lang.Comparing;
import consulo.util.lang.Couple;
import consulo.util.lang.StringUtil;
import consulo.util.lang.ref.SimpleReference;
import consulo.virtualFileSystem.LocalFileSystem;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import mono.debugger.*;
import mono.debugger.protocol.Method_GetDebugInfo;
import mono.debugger.request.BreakpointRequest;
import mono.debugger.request.EventRequestManager;
import mono.debugger.request.ExceptionRequest;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.*;

/**
 * @author VISTALL
 * @since 18.04.2016
 */
public class MonoBreakpointUtil
{
	public static class FindLocationResult
	{
		public static final FindLocationResult WRONG_TARGET = new FindLocationResult();

		public static final FindLocationResult NO_LOCATIONS = new FindLocationResult();

		private Collection<Location> myLocations = Collections.emptyList();

		@Nonnull
		public static FindLocationResult success(@Nonnull Collection<Location> locations)
		{
			FindLocationResult findLocationResult = new FindLocationResult();
			findLocationResult.myLocations = locations;
			return findLocationResult;
		}

		@Nonnull
		public Collection<Location> getLocations()
		{
			return myLocations;
		}
	}


	@Nullable
	@SuppressWarnings("unchecked")
	public static String getTypeQNameFromBreakpoint(@Nonnull Project project, @Nonnull XBreakpoint<?> breakpoint)
	{
		if(breakpoint instanceof XLineBreakpoint)
		{
			return getTypeQNameFromLineBreakpoint(project, (XLineBreakpoint<?>) breakpoint);
		}

		if(breakpoint.getType() == DotNetExceptionBreakpointType.getInstance())
		{
			return getTypeQNameFromExceptionBreakpoint((XBreakpoint<DotNetExceptionBreakpointProperties>) breakpoint);
		}
		return null;
	}

	@Nullable
	private static String getTypeQNameFromExceptionBreakpoint(@Nonnull XBreakpoint<DotNetExceptionBreakpointProperties> breakpoin)
	{
		String vmQName = breakpoin.getProperties().VM_QNAME;
		if(!StringUtil.isEmpty(vmQName))
		{
			return vmQName;
		}
		return null;
	}

	@Nullable
	private static String getTypeQNameFromLineBreakpoint(@Nonnull Project project, @Nonnull final XLineBreakpoint<?> breakpoint)
	{
		final VirtualFile fileByUrl = VirtualFileManager.getInstance().findFileByUrl(breakpoint.getFileUrl());
		if(fileByUrl == null)
		{
			return null;
		}

		return AccessRule.read(() ->
		{
			PsiFile file = PsiManager.getInstance(project).findFile(fileByUrl);
			if(file == null)
			{
				return null;
			}
			PsiElement psiElement = DotNetDebuggerUtil.findPsiElement(file, breakpoint.getLine());
			if(psiElement == null)
			{
				return null;
			}
			DotNetDebuggerSourceLineResolver resolver = DotNetDebuggerSourceLineResolver.forLanguage(file.getLanguage());
			assert resolver != null;
			return resolver.resolveParentVmQName(psiElement);
		});
	}

	public static void createMethodRequest(final XDebugSession session, @Nonnull MonoVirtualMachineProxy virtualMachine, @Nonnull final XLineBreakpoint<DotNetMethodBreakpointProperties> breakpoint)
	{
		/*
		virtualMachine.stopBreakpointRequests(breakpoint);

		EventRequestManager eventRequestManager = virtualMachine.getDelegate().eventRequestManager();

		MethodEntryRequest methodEntryRequest = eventRequestManager.createMethodEntryRequest();
		methodEntryRequest.setSuspendPolicy(mono.debugger.SuspendPolicy.ALL);
		methodEntryRequest.setEnabled(breakpoint.isEnabled());

		virtualMachine.putRequest(breakpoint, methodEntryRequest); */
	}

	public static void createExceptionRequest(@Nonnull XDebugSession session,
											  @Nonnull MonoVirtualMachineProxy virtualMachine,
											  @Nonnull XBreakpoint<DotNetExceptionBreakpointProperties> breakpoint,
											  @Nullable TypeMirror typeMirror)
	{
		DotNetExceptionBreakpointProperties properties = breakpoint.getProperties();

		if(typeMirror != null && !Comparing.equal(properties.VM_QNAME, typeMirror.fullName()) || StringUtil.isEmpty(properties.VM_QNAME) && typeMirror != null)
		{
			return;
		}

		virtualMachine.disposeAllRelatedDataForBreakpoint(breakpoint, true);

		EventRequestManager eventRequestManager = virtualMachine.getDelegate().eventRequestManager();

		ExceptionRequest exceptionRequest = eventRequestManager.createExceptionRequest(typeMirror, properties.NOTIFY_CAUGHT, properties.NOTIFY_UNCAUGHT, properties.SUBCLASSES);
		exceptionRequest.setSuspendPolicy(mono.debugger.SuspendPolicy.ALL);
		exceptionRequest.setEnabled(breakpoint.isEnabled());

		virtualMachine.putRequest(breakpoint, exceptionRequest);

		virtualMachine.enableTypeRequest(breakpoint, getTypeQNameFromBreakpoint(session.getProject(), breakpoint));
	}

	public static void createBreakpointRequest(@Nonnull XDebugSession debugSession,
											   @Nonnull MonoVirtualMachineProxy virtualMachine,
											   @Nonnull XLineBreakpoint<?> breakpoint,
											   @Nullable TypeMirror typeMirror,
											   boolean insertTypeLoad)
	{
		try
		{
			Project project = debugSession.getProject();

			FindLocationResult result = findLocationsImpl(project, virtualMachine, breakpoint, typeMirror);
			if(result == FindLocationResult.WRONG_TARGET)
			{
				return;
			}

			virtualMachine.disposeAllRelatedDataForBreakpoint(breakpoint, insertTypeLoad);

			Collection<Location> locations = result.getLocations();
			if(breakpoint.getSuspendPolicy() != consulo.execution.debug.breakpoint.SuspendPolicy.NONE)
			{
				for(Location location : locations)
				{
					EventRequestManager eventRequestManager = virtualMachine.eventRequestManager();
					BreakpointRequest breakpointRequest = eventRequestManager.createBreakpointRequest(location);
					breakpointRequest.setEnabled(breakpoint.isEnabled());

					virtualMachine.putRequest(breakpoint, breakpointRequest);
				}
			}

			if(insertTypeLoad)
			{
				virtualMachine.enableTypeRequest(breakpoint, getTypeQNameFromBreakpoint(project, breakpoint));
			}

			DotNetBreakpointUtil.updateLineBreakpointIcon(project, !locations.isEmpty(), breakpoint);
		}
		catch(Exception ignored)
		{
		}
	}

	@Nonnull
	private static FindLocationResult findLocationsImpl(@Nonnull final Project project,
														@Nonnull final MonoVirtualMachineProxy virtualMachine,
														@Nonnull final XLineBreakpoint<?> lineBreakpoint,
														@Nullable final TypeMirror typeMirror) throws TypeMirrorUnloadedException
	{
		final int line = lineBreakpoint.getLine();
		final VirtualFile fileByUrl = VirtualFileManager.getInstance().findFileByUrl(lineBreakpoint.getFileUrl());
		final DotNetLineBreakpointProperties properties = (DotNetLineBreakpointProperties) lineBreakpoint.getProperties();
		return findLocationsImpl(project, virtualMachine, fileByUrl, line, properties.getExecutableChildrenAtLineIndex(), typeMirror);
	}

	@Nonnull
	public static FindLocationResult findLocationsImpl(@Nonnull final Project project,
													   @Nonnull final MonoVirtualMachineProxy virtualMachine,
													   @Nullable final VirtualFile targetVFile,
													   final int breakpointLine,
													   @Nullable final Integer executableChildrenAtLineIndex,
													   @Nullable final TypeMirror typeMirror) throws TypeMirrorUnloadedException
	{
		if(targetVFile == null)
		{
			return FindLocationResult.WRONG_TARGET;
		}

		final PsiFile file = AccessRule.read(() -> PsiManager.getInstance(project).findFile(targetVFile));

		if(file == null)
		{
			return FindLocationResult.WRONG_TARGET;
		}

		final SimpleReference<DotNetDebuggerSourceLineResolver> resolverRef = SimpleReference.create();

		final String vmQualifiedName = AccessRule.read(() ->
		{
			PsiElement psiElement = DotNetDebuggerUtil.findPsiElement(file, breakpointLine);
			if(psiElement == null)
			{
				return null;
			}
			DotNetDebuggerSourceLineResolver resolver = DotNetDebuggerSourceLineResolver.forLanguage(file.getLanguage());
			assert resolver != null;
			resolverRef.set(resolver);
			return resolver.resolveParentVmQName(psiElement);
		});

		if(vmQualifiedName == null)
		{
			return FindLocationResult.WRONG_TARGET;
		}

		if(typeMirror != null && !Comparing.equal(vmQualifiedName, DotNetDebuggerUtil.getVmQName(typeMirror.fullName())))
		{
			return FindLocationResult.WRONG_TARGET;
		}

		TypeMirror mirror = typeMirror == null ? virtualMachine.findTypeMirror(project, targetVFile, vmQualifiedName) : typeMirror;

		if(mirror == null)

		{
			return FindLocationResult.NO_LOCATIONS;
		}

		Map<MethodMirror, Location> methods = new LinkedHashMap<>();

		try
		{
			for(MethodMirror methodMirror : mirror.methods())
			{
				if(methods.containsKey(methodMirror))
				{
					continue;
				}

				if(executableChildrenAtLineIndex != null)
				{
					Couple<String> lambdaInfo = DotNetDebuggerCompilerGenerateUtil.extractLambdaInfo(new MonoMethodProxy(methodMirror));
					if(executableChildrenAtLineIndex == -1 && lambdaInfo != null)
					{
						// is lambda - we cant enter it with -1
						continue;
					}

					if(executableChildrenAtLineIndex != -1)
					{
						if(lambdaInfo == null)
						{
							continue;
						}

						final Method_GetDebugInfo.Entry[] entries = methodMirror.debugInfo();
						if(entries.length == 0)
						{
							continue;
						}

						boolean acceptable = AccessRule.read(() -> findExecutableElementFromDebugInfo(project, entries, executableChildrenAtLineIndex) != null);

						if(!acceptable)
						{
							continue;
						}
					}
				}

				collectLocations(virtualMachine, breakpointLine, methods, methodMirror, targetVFile);
			}

			TypeMirror[] nestedTypeMirrors = mirror.nestedTypes();
			for(TypeMirror nestedTypeMirror : nestedTypeMirrors)
			{
				MonoTypeProxy typeProxy = MonoTypeProxy.of(nestedTypeMirror);
				if(DotNetDebuggerCompilerGenerateUtil.isYieldOrAsyncNestedType(typeProxy))
				{
					// we interest only MoveNext method
					MethodMirror moveNext = nestedTypeMirror.findMethodByName("MoveNext", false);
					if(moveNext != null)
					{
						collectLocations(virtualMachine, breakpointLine, methods, moveNext, targetVFile);
					}
				}
				else if(DotNetDebuggerCompilerGenerateUtil.isAsyncLambdaWrapper(typeProxy))
				{
					TypeMirror[] typeMirrors = nestedTypeMirror.nestedTypes();
					if(typeMirrors.length > 0)
					{
						MethodMirror moveNext = typeMirrors[0].findMethodByName("MoveNext", false);

						if(moveNext != null)
						{
							collectLocations(virtualMachine, breakpointLine, methods, moveNext, targetVFile);
						}
					}

					for(MethodMirror nestedMetohdMirror : nestedTypeMirror.methods())
					{
						collectLocations(virtualMachine, breakpointLine, methods, nestedMetohdMirror, targetVFile);
					}
				}
			}
		}
		catch(UnloadedElementException e)
		{
			throw new TypeMirrorUnloadedException(mirror, e);
		}

		return methods.isEmpty() ? FindLocationResult.NO_LOCATIONS : FindLocationResult.success(methods.values());
	}

	@RequiredReadAction
	public static PsiElement findExecutableElementFromDebugInfo(final Project project, Method_GetDebugInfo.Entry[] entries, int index)
	{
		Method_GetDebugInfo.Entry entry = entries[0];
		Method_GetDebugInfo.SourceFile sourceFile = entry.sourceFile;
		if(sourceFile == null)
		{
			return null;
		}
		final VirtualFile fileByPath = LocalFileSystem.getInstance().findFileByPath(sourceFile.name);
		if(fileByPath == null)
		{
			return null;
		}

		PsiFile otherPsiFile = PsiManager.getInstance(project).findFile(fileByPath);
		if(otherPsiFile == null)
		{
			return null;
		}

		PsiElement psiElement = DotNetDebuggerUtil.findPsiElement(otherPsiFile, entry.line - 1, entry.column);
		if(psiElement == null)
		{
			return null;
		}

		Set<PsiElement> psiElements = DotNetLineBreakpointType.collectExecutableChildren(otherPsiFile, entry.line - 1);
		if(psiElements.isEmpty())
		{
			return null;
		}

		PsiElement[] array = ContainerUtil.toArray(psiElements, PsiElement.ARRAY_FACTORY);

		// inc + 1, we dont need go absolute parent
		PsiElement executableTarget = ArrayUtil2.safeGet(array, index + 1);
		if(executableTarget == null)
		{
			return null;
		}

		return PsiTreeUtil.isAncestor(executableTarget, psiElement, true) ? executableTarget : null;
	}

	private static void collectLocations(MonoVirtualMachineProxy virtualMachine, int breakpointLine, Map<MethodMirror, Location> methods, MethodMirror methodMirror, VirtualFile targetVFile)
	{
		IntSet registeredLines = IntSets.newHashSet();
		for(Method_GetDebugInfo.Entry entry : methodMirror.debugInfo())
		{
			if(entry.line == (breakpointLine + 1))
			{
				if(!registeredLines.add(entry.line))
				{
					continue;
				}

				Method_GetDebugInfo.SourceFile sourceFile = entry.sourceFile;
				if(sourceFile != null && sourceFile.name != null)
				{
					VirtualFile sourceVFile = LocalFileSystem.getInstance().findFileByPath(sourceFile.name);
					if(sourceVFile != null && !sourceVFile.equals(targetVFile))
					{
						continue;
					}
				}

				methods.put(methodMirror, new LocationImpl(virtualMachine.getDelegate(), methodMirror, entry.offset));
			}
		}
	}
}
