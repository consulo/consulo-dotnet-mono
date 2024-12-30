/*
 * Copyright 2013-2014 must-be.org
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

package consulo.dotnet.mono.debugger;

import consulo.annotation.UsedInPlugin;
import consulo.application.AccessRule;
import consulo.application.util.function.Processor;
import consulo.dotnet.debugger.DotNetDebugContext;
import consulo.dotnet.debugger.impl.DotNetDebugProcessBase;
import consulo.dotnet.debugger.impl.DotNetSuspendContext;
import consulo.dotnet.debugger.impl.breakpoint.DotNetBreakpointEngine;
import consulo.dotnet.debugger.impl.breakpoint.DotNetBreakpointUtil;
import consulo.dotnet.debugger.impl.breakpoint.properties.DotNetExceptionBreakpointProperties;
import consulo.dotnet.debugger.impl.breakpoint.properties.DotNetMethodBreakpointProperties;
import consulo.dotnet.debugger.proxy.DotNetNotSuspendedException;
import consulo.dotnet.mono.debugger.breakpoint.MonoBreakpointUtil;
import consulo.dotnet.mono.debugger.proxy.MonoThreadProxy;
import consulo.dotnet.mono.debugger.proxy.MonoVirtualMachineProxy;
import consulo.dotnet.psi.DotNetTypeDeclaration;
import consulo.dotnet.util.DebugConnectionInfo;
import consulo.execution.debug.XDebugSession;
import consulo.execution.debug.breakpoint.XBreakpoint;
import consulo.execution.debug.breakpoint.XLineBreakpoint;
import consulo.execution.ui.console.ConsoleView;
import consulo.execution.ui.console.ConsoleViewContentType;
import consulo.language.editor.util.PsiUtilBase;
import consulo.logging.Logger;
import consulo.proxy.EventDispatcher;
import consulo.util.lang.Comparing;
import consulo.util.lang.StringUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.VirtualFileManager;
import mono.debugger.*;
import mono.debugger.connect.Connector;
import mono.debugger.event.*;
import mono.debugger.request.EventRequest;
import mono.debugger.request.TypeLoadRequest;

import jakarta.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * @author VISTALL
 * @since 10.04.14
 */
public class MonoDebugThread extends Thread
{
	private static final Logger LOGGER = Logger.getInstance(MonoDebugThread.class);

	private static final ThreadGroup ourThreadGroup = new ThreadGroup("Mono Soft Debugger");

	private final XDebugSession mySession;
	private final MonoDebugProcess myDebugProcess;
	private final DebugConnectionInfo myDebugConnectionInfo;
	private final DotNetBreakpointEngine myBreakpointEngine = new DotNetBreakpointEngine();
	private final Queue<Processor<MonoVirtualMachineProxy>> myQueue = new ConcurrentLinkedQueue<Processor<MonoVirtualMachineProxy>>();
	private final EventDispatcher<MonoVirtualMachineListener> myEventDispatcher = EventDispatcher.create(MonoVirtualMachineListener.class);

	private volatile MonoVirtualMachineProxy myVirtualMachine;
	private boolean myStop;

	public MonoDebugThread(XDebugSession session, MonoDebugProcess debugProcess, DebugConnectionInfo debugConnectionInfo)
	{
		super(ourThreadGroup, "MonoDebugThread: " + new Random().nextInt());
		mySession = session;
		myDebugProcess = debugProcess;
		myDebugConnectionInfo = debugConnectionInfo;
	}

	@UsedInPlugin
	public void addListener(MonoVirtualMachineListener listener)
	{
		myEventDispatcher.addListener(listener);
	}

	@UsedInPlugin
	public void removeListener(MonoVirtualMachineListener listener)
	{
		myEventDispatcher.removeListener(listener);
	}

	public void connectionStopped()
	{
		myStop = true;
		myEventDispatcher.getMulticaster().connectionStopped();

		if(myVirtualMachine != null)
		{
			try
			{
				myVirtualMachine.dispose();
			}
			catch(Exception e)
			{
				//
			}
		}
		myVirtualMachine = null;
	}

	@Override
	public void run()
	{
		VirtualMachine virtualMachine = null;
		if(!myDebugConnectionInfo.isServer())
		{
			SocketListeningConnector l = new SocketListeningConnector();
			Map<String, Connector.Argument> argumentMap = l.defaultArguments();
			argumentMap.get(SocketListeningConnector.ARG_LOCALADDR).setValue(myDebugConnectionInfo.getHost());
			argumentMap.get(SocketListeningConnector.ARG_PORT).setValue(String.valueOf(myDebugConnectionInfo.getPort()));
			argumentMap.get("timeout").setValue("10000");


			try
			{
				virtualMachine = l.accept(argumentMap);
			}
			catch(Exception e)
			{
				//
			}
		}
		else
		{
			SocketAttachingConnector l = new SocketAttachingConnector();
			Map<String, Connector.Argument> argumentMap = l.defaultArguments();
			argumentMap.get("hostname").setValue(myDebugConnectionInfo.getHost());
			argumentMap.get("port").setValue(String.valueOf(myDebugConnectionInfo.getPort()));
			argumentMap.get("timeout").setValue("10000");

			int tryCount = 5;
			while(tryCount != 0)
			{
				try
				{
					virtualMachine = l.attach(argumentMap);
					break;
				}
				catch(Exception e)
				{
					tryCount--;
				}
			}
		}

		if(virtualMachine == null)
		{
			myEventDispatcher.getMulticaster().connectionFailed();
			return;
		}
		else
		{
			myEventDispatcher.getMulticaster().connectionSuccess(virtualMachine);
		}

		myVirtualMachine = new MonoVirtualMachineProxy(virtualMachine);

		virtualMachine.enableEvents(/*EventKind.ASSEMBLY_LOAD, EventKind.THREAD_START, EventKind.THREAD_DEATH, EventKind.ASSEMBLY_UNLOAD,*/
				EventKind.USER_BREAK, EventKind.USER_LOG, EventKind.APPDOMAIN_CREATE, EventKind.APPDOMAIN_UNLOAD);

		for(XLineBreakpoint<?> breakpoint : myDebugProcess.getLineBreakpoints())
		{
			myVirtualMachine.enableTypeRequest(breakpoint, MonoBreakpointUtil.getTypeQNameFromBreakpoint(mySession.getProject(), breakpoint));

			DotNetBreakpointUtil.updateLineBreakpointIcon(mySession.getProject(), null, breakpoint);
		}

		Collection<? extends XBreakpoint<DotNetExceptionBreakpointProperties>> exceptionBreakpoints = myDebugProcess.getExceptionBreakpoints();
		for(XBreakpoint<DotNetExceptionBreakpointProperties> exceptionBreakpoint : exceptionBreakpoints)
		{
			String vmQName = exceptionBreakpoint.getProperties().VM_QNAME;
			if(!StringUtil.isEmpty(vmQName))
			{
				continue;
			}
			MonoBreakpointUtil.createExceptionRequest(getSession(), myVirtualMachine, exceptionBreakpoint, null);
		}

		Collection<? extends XLineBreakpoint<DotNetMethodBreakpointProperties>> methodBreakpoints = myDebugProcess.getMethodBreakpoints();
		for(XLineBreakpoint<DotNetMethodBreakpointProperties> lineBreakpoint : methodBreakpoints)
		{
			MonoBreakpointUtil.createMethodRequest(mySession, myVirtualMachine, lineBreakpoint);
		}

		if(!myVirtualMachine.isSupportTypeRequestByName())
		{
			TypeLoadRequest typeLoadRequest = virtualMachine.eventRequestManager().createTypeLoadRequest();
			typeLoadRequest.enable();
		}

		try
		{
			virtualMachine.eventQueue().remove();  // Wait VMStart
			try
			{
				virtualMachine.resume();
			}
			catch(Exception e)
			{
				//
			}
		}
		catch(InterruptedException e)
		{
			LOGGER.error(e);
			myEventDispatcher.getMulticaster().connectionFailed();
			return;
		}

		while(!myStop)
		{
			processCommands(myVirtualMachine);

			EventQueue eventQueue = virtualMachine.eventQueue();
			EventSet eventSet;
			try
			{
				boolean stopped = false;
				boolean focusUI = false;

				while((eventSet = eventQueue.remove(1)) != null)
				{
					for(final Event event : eventSet)
					{
						if(event instanceof BreakpointEvent)
						{
							stopped = true;

							EventRequest request = event.request();
							XBreakpoint<?> breakpoint = request == null ? null : myVirtualMachine.findBreakpointByRequest(request);
							DotNetDebugContext debugContext = myDebugProcess.createDebugContext(myVirtualMachine, breakpoint);
							if(breakpoint != null)
							{
								MonoThreadProxy threadProxy = new MonoThreadProxy(myVirtualMachine, eventSet.eventThread());

								final String message = myBreakpointEngine.tryEvaluateBreakpointLogMessage(threadProxy, (XLineBreakpoint<?>) breakpoint, debugContext);

								if(myBreakpointEngine.tryEvaluateBreakpointCondition(threadProxy, (XLineBreakpoint<?>) breakpoint, debugContext))
								{
									DotNetSuspendContext suspendContext = new DotNetSuspendContext(debugContext, MonoThreadProxy.getIdFromThread(myVirtualMachine, eventSet.eventThread()));

									mySession.breakpointReached(breakpoint, message, suspendContext);
								}
								else
								{
									stopped = false;
								}
							}
							else
							{
								if(request != null)
								{
									final Object property = request.getProperty(DotNetDebugProcessBase.RUN_TO_CURSOR);
									if(property != null)
									{
										request.delete();
									}
								}

								mySession.positionReached(new DotNetSuspendContext(debugContext, MonoThreadProxy.getIdFromThread(myVirtualMachine, eventSet.eventThread())));
								focusUI = true;
							}
						}
						else if(event instanceof StepEvent)
						{
							DotNetDebugContext context = myDebugProcess.createDebugContext(myVirtualMachine, null);

							mySession.positionReached(new DotNetSuspendContext(context, MonoThreadProxy.getIdFromThread(myVirtualMachine, eventSet.eventThread())));
							stopped = true;
						}
						else if(event instanceof UserBreakEvent)
						{
							DotNetDebugContext context = myDebugProcess.createDebugContext(myVirtualMachine, null);
							mySession.positionReached(new DotNetSuspendContext(context, MonoThreadProxy.getIdFromThread(myVirtualMachine, eventSet.eventThread())));
							stopped = true;
							focusUI = true;
						}
						else if(event instanceof AppDomainCreateEvent)
						{
							AppDomainMirror appDomainMirror = ((AppDomainCreateEvent) event).getAppDomainMirror();
							myVirtualMachine.loadAppDomain(appDomainMirror);
						}
						else if(event instanceof AppDomainUnloadEvent)
						{
							AppDomainMirror appDomainMirror = ((AppDomainUnloadEvent) event).getAppDomainMirror();
							myVirtualMachine.unloadAppDomain(appDomainMirror);
						}
						else if(event instanceof TypeLoadEvent)
						{
							TypeMirror typeMirror = ((TypeLoadEvent) event).typeMirror();

							insertBreakpoints(myVirtualMachine, typeMirror);
						}
						else if(event instanceof VMDeathEvent)
						{
							connectionStopped();
							return;
						}
						else if(event instanceof UserLogEvent)
						{
							//int level = ((UserLogEvent) event).getLevel();
							String category = ((UserLogEvent) event).getCategory();
							String message = ((UserLogEvent) event).getMessage();

							ConsoleView consoleView = mySession.getConsoleView();
							consoleView.print("[" + category + "] " + message + "\n", ConsoleViewContentType.USER_INPUT);
						}
						else if(event instanceof ExceptionEvent)
						{
							XBreakpoint<?> breakpoint = myVirtualMachine.findBreakpointByRequest(event.request());
							DotNetDebugContext context = myDebugProcess.createDebugContext(myVirtualMachine, breakpoint);

							DotNetSuspendContext suspendContext = new DotNetSuspendContext(context, MonoThreadProxy.getIdFromThread(myVirtualMachine, eventSet.eventThread()));
							if(breakpoint != null)
							{
								mySession.breakpointReached(breakpoint, null, suspendContext);
							}
							else
							{
								mySession.positionReached(suspendContext);
								focusUI = true;
							}
							stopped = true;
						}
						else if(event instanceof MethodEntryEvent)
						{
							//
						}
						else if(event instanceof MethodExitEvent)
						{
							//
						}
						else
						{
							LOGGER.error("Unknown event " + event.getClass().getSimpleName());
						}
					}

					if(stopped)
					{
						myVirtualMachine.stopStepRequests();

						myDebugProcess.setPausedEventSet(eventSet);
						
						break;
					}
					else
					{
						try
						{
							virtualMachine.resume();
							break;
						}
						catch(NotSuspendedException ignored)
						{
							// when u attached - app is not suspended
						}
					}
				}
			}
			catch(VMDisconnectedException | IOException e)
			{
				connectionStopped();
			}
			catch(DotNetNotSuspendedException e)
			{
				// dont interest
			}
			catch(Throwable e)
			{
				LOGGER.error(e);
			}
		}
	}

	private void insertBreakpoints(final MonoVirtualMachineProxy virtualMachine, final TypeMirror typeMirror)
	{
		final DotNetDebugContext debugContext = myDebugProcess.createDebugContext(virtualMachine, null);

		Collection<? extends XBreakpoint<DotNetExceptionBreakpointProperties>> exceptionBreakpoints = myDebugProcess.getExceptionBreakpoints();
		for(XBreakpoint<DotNetExceptionBreakpointProperties> exceptionBreakpoint : exceptionBreakpoints)
		{
			MonoBreakpointUtil.createExceptionRequest(getSession(), myVirtualMachine, exceptionBreakpoint, typeMirror);
		}

		DotNetTypeDeclaration[] typeDeclarations = AccessRule.read(() -> MonoDebugUtil.findTypesByQualifiedName(typeMirror, debugContext));

		if(typeDeclarations.length > 0)
		{
			Collection<? extends XLineBreakpoint<?>> breakpoints = myDebugProcess.getLineBreakpoints();
			for(DotNetTypeDeclaration dotNetTypeDeclaration : typeDeclarations)
			{
				VirtualFile typeVirtualFile = PsiUtilBase.getVirtualFile(dotNetTypeDeclaration);

				for(final XLineBreakpoint<?> breakpoint : breakpoints)
				{
					VirtualFile lineBreakpoint = VirtualFileManager.getInstance().findFileByUrl(breakpoint.getFileUrl());
					if(!Comparing.equal(typeVirtualFile, lineBreakpoint))
					{
						continue;
					}

					MonoBreakpointUtil.createBreakpointRequest(mySession, virtualMachine, breakpoint, typeMirror, false);
				}
			}
		}
	}

	private void processCommands(MonoVirtualMachineProxy virtualMachine)
	{
		Processor<MonoVirtualMachineProxy> processor;
		while((processor = myQueue.poll()) != null)
		{
			if(processor.process(virtualMachine))
			{
				virtualMachine.resume();
			}
		}
	}

	public XDebugSession getSession()
	{
		return mySession;
	}

	public void invoke(@Nonnull Consumer<MonoVirtualMachineProxy> processor)
	{
		MonoVirtualMachineProxy vm = myVirtualMachine;
		if(vm == null)
		{
			return;
		}
		vm.invoke(() -> processor.accept(vm));
	}

	public void addCommand(Processor<MonoVirtualMachineProxy> processor)
	{
		if(!isConnected())
		{
			return;
		}

		myQueue.add(processor);
	}

	public boolean isConnected()
	{
		return myVirtualMachine != null;
	}
}
