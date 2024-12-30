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

package consulo.dotnet.mono.debugger;

import jakarta.annotation.Nonnull;

import mono.debugger.TypeMirror;

/**
 * @author VISTALL
 * @since 15.05.2015
 */
public class TypeMirrorUnloadedException  extends Exception
{
	private final String myFullName;

	public TypeMirrorUnloadedException(@Nonnull TypeMirror typeMirror, Exception e)
	{
		super("TypeMirror " + typeMirror.fullName() + " is unloaded", e);
		myFullName = typeMirror.fullName();
	}

	@Nonnull
	public String getFullName()
	{
		return myFullName;
	}
}
