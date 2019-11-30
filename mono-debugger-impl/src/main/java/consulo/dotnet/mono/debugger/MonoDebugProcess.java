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

import com.intellij.execution.configurations.RunProfile;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerBundle;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.*;
import com.intellij.xdebugger.frame.XSuspendContext;
import consulo.annotation.access.RequiredReadAction;
import consulo.dotnet.debugger.DotNetDebugProcessBase;
import consulo.dotnet.debugger.DotNetSuspendContext;
import consulo.dotnet.debugger.breakpoint.DotNetExceptionBreakpointType;
import consulo.dotnet.debugger.breakpoint.DotNetLineBreakpointType;
import consulo.dotnet.debugger.breakpoint.properties.DotNetExceptionBreakpointProperties;
import consulo.dotnet.execution.DebugConnectionInfo;
import consulo.dotnet.mono.debugger.breakpoint.MonoBreakpointUtil;
import mono.debugger.Location;
import mono.debugger.NoInvocationException;
import mono.debugger.ThreadMirror;
import mono.debugger.VMDisconnectedException;
import mono.debugger.event.EventSet;
import mono.debugger.request.BreakpointRequest;
import mono.debugger.request.EventRequestManager;
import mono.debugger.request.StepRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;

/**
 * @author VISTALL
 * @since 10.04.14
 */
public class MonoDebugProcess extends DotNetDebugProcessBase
{
	private class MyXBreakpointListener implements XBreakpointListener<XBreakpoint<?>>
	{
		@Override
		public void breakpointAdded(@Nonnull final XBreakpoint<?> breakpoint)
		{
			myDebugThread.invoke(virtualMachine ->
			{
				XBreakpointType<?, ?> type = breakpoint.getType();
				if(type == DotNetLineBreakpointType.getInstance())
				{
					MonoBreakpointUtil.createBreakpointRequest(getSession(), virtualMachine, (XLineBreakpoint) breakpoint, null, true);
				}
				else if(type == DotNetExceptionBreakpointType.getInstance())
				{
					MonoBreakpointUtil.createExceptionRequest(getSession(), virtualMachine, (XBreakpoint<DotNetExceptionBreakpointProperties>) breakpoint, null);
				}
			});
		}

		@Override
		public void breakpointRemoved(@Nonnull final XBreakpoint<?> breakpoint)
		{
			myDebugThread.invoke(virtualMachine -> virtualMachine.disposeAllRelatedDataForBreakpoint(breakpoint, true));
		}

		@Override
		public void breakpointChanged(@Nonnull XBreakpoint<?> breakpoint)
		{
			if(breakpoint.isEnabled())
			{
				breakpointAdded(breakpoint);
			}
			else
			{
				breakpointRemoved(breakpoint);
			}
		}
	}

	private final DebugConnectionInfo myDebugConnectionInfo;
	private final MonoDebugThread myDebugThread;

	private EventSet myPausedEventSet;
	private XBreakpointManager myBreakpointManager;
	private final XBreakpointListener<XBreakpoint<?>> myBreakpointListener = new MyXBreakpointListener();

	public MonoDebugProcess(XDebugSession session, RunProfile runProfile, DebugConnectionInfo debugConnectionInfo)
	{
		super(session, runProfile);
		session.setPauseActionSupported(true);
		myDebugConnectionInfo = debugConnectionInfo;
		myDebugThread = new MonoDebugThread(session, this, myDebugConnectionInfo);

		myBreakpointManager = XDebuggerManager.getInstance(session.getProject()).getBreakpointManager();
		myBreakpointManager.addBreakpointListener(myBreakpointListener);
	}

	@Nonnull
	public MonoDebugThread getDebugThread()
	{
		return myDebugThread;
	}

	@Override
	public void start()
	{
		myDebugThread.start();
	}

	@Override
	public void startPausing()
	{
		myDebugThread.addCommand(virtualMachine ->
		{
			virtualMachine.suspend();
			getSession().positionReached(new DotNetSuspendContext(createDebugContext(virtualMachine, null), -1));
			return false;
		});
	}

	@Override
	@RequiredReadAction
	public void runToPosition(@Nonnull final XSourcePosition position, @Nullable XSuspendContext context)
	{
		if(myPausedEventSet == null)
		{
			return;
		}

		myDebugThread.addCommand(virtualMachine ->
		{
			virtualMachine.stopStepRequests();

			try
			{
				final MonoBreakpointUtil.FindLocationResult result = MonoBreakpointUtil.findLocationsImpl(getSession().getProject(), virtualMachine, position.getFile(),
						position.getLine(), null, null);

				// no target for execution
				final Collection<Location> locations = result.getLocations();
				if(locations.isEmpty())
				{
					return true;
				}

				for(Location location : locations)
				{
					EventRequestManager eventRequestManager = virtualMachine.eventRequestManager();
					BreakpointRequest breakpointRequest = eventRequestManager.createBreakpointRequest(location);
					breakpointRequest.putProperty(RUN_TO_CURSOR, Boolean.TRUE);
					breakpointRequest.setEnabled(true);
				}
			}
			catch(TypeMirrorUnloadedException ignored)
			{
			}
			return true;
		});
	}

	@Override
	public void resume(@Nullable XSuspendContext context)
	{
		myPausedEventSet = null;
		myDebugThread.addCommand(virtualMachine ->
		{
			virtualMachine.stopStepRequests();

			return true;
		});
	}

	@Override
	public String getCurrentStateMessage()
	{
		if(myDebugThread.isConnected())
		{
			return "Connected to " + myDebugConnectionInfo.getHost() + ":" + myDebugConnectionInfo.getPort();
		}
		return XDebuggerBundle.message("debugger.state.message.disconnected");
	}

	@Override
	public void startStepOver(@Nullable XSuspendContext context)
	{
		stepRequest(StepRequest.StepDepth.Over, StepRequest.StepSize.Line);
	}

	@Override
	public void startStepInto(@Nullable XSuspendContext context)
	{
		stepRequest(StepRequest.StepDepth.Into, StepRequest.StepSize.Line);
	}

	@Override
	public void startStepOut(@Nullable XSuspendContext context)
	{
		stepRequest(StepRequest.StepDepth.Out, StepRequest.StepSize.Line);
	}

	private void stepRequest(final StepRequest.StepDepth stepDepth, final StepRequest.StepSize stepSize)
	{
		if(myPausedEventSet == null)
		{
			return;
		}

		final ThreadMirror threadMirror = myPausedEventSet.eventThread();
		if(threadMirror == null)
		{
			return;
		}

		myDebugThread.addCommand(virtualMachine ->
		{
			try
			{
				EventRequestManager eventRequestManager = virtualMachine.eventRequestManager();
				StepRequest stepRequest = eventRequestManager.createStepRequest(threadMirror, stepSize, stepDepth);
				stepRequest.enable();

				virtualMachine.addStepRequest(stepRequest);
			}
			catch(NoInvocationException | VMDisconnectedException ignored)
			{
			}
			return true;
		});
	}

	@Override
	public void stopImpl()
	{
		myPausedEventSet = null;
		myDebugThread.connectionStopped();
		normalizeBreakpoints();
		myBreakpointManager.removeBreakpointListener(myBreakpointListener);
	}

	public void setPausedEventSet(EventSet pausedEventSet)
	{
		myPausedEventSet = pausedEventSet;
	}
}
