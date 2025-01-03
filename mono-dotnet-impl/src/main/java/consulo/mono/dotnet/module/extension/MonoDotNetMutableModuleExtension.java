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

import consulo.content.bundle.Sdk;
import consulo.disposer.Disposable;
import consulo.dotnet.module.extension.DotNetConfigurationPanel;
import consulo.dotnet.module.extension.DotNetMutableModuleExtension;
import consulo.dotnet.psi.impl.module.extension.DotNetQualifiedElementQualifierProducer;
import consulo.module.content.layer.ModuleRootLayer;
import consulo.module.extension.MutableModuleInheritableNamedPointer;
import consulo.module.extension.swing.SwingMutableModuleExtension;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.VerticalLayout;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javax.swing.*;

/**
 * @author VISTALL
 * @since 20.11.13.
 */
public class MonoDotNetMutableModuleExtension extends MonoDotNetModuleExtension implements DotNetMutableModuleExtension<MonoDotNetModuleExtension>, SwingMutableModuleExtension
{
	public MonoDotNetMutableModuleExtension(@Nonnull String id, @Nonnull ModuleRootLayer rootModel)
	{
		super(id, rootModel);
	}

	@Nonnull
	@Override
	public MutableModuleInheritableNamedPointer<Sdk> getInheritableSdk()
	{
		return (MutableModuleInheritableNamedPointer<Sdk>) super.getInheritableSdk();
	}

	@RequiredUIAccess
	@Nullable
	@Override
	public Component createConfigurationComponent(@Nonnull Disposable disposable, @Nonnull Runnable runnable)
	{
		return VerticalLayout.create().add(Label.create("Unsupported platform"));
	}

	@RequiredUIAccess
	@Nullable
	@Override
	public JComponent createConfigurablePanel(@Nonnull Disposable disposable, @Nonnull Runnable runnable)
	{
		return new DotNetConfigurationPanel(this, DotNetQualifiedElementQualifierProducer.INSTANCE, myVariables, runnable);
	}

	@Override
	public void setEnabled(boolean b)
	{
		myIsEnabled = b;
	}

	@Override
	public boolean isModified(@Nonnull MonoDotNetModuleExtension extension)
	{
		return isModifiedImpl(extension);
	}
}
