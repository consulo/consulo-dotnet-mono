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

package consulo.mono.dotnet.documentation;

import consulo.annotation.access.RequiredReadAction;
import consulo.annotation.component.ExtensionImpl;
import consulo.dotnet.documentation.DotNetDocumentationResolver;
import consulo.dotnet.psi.DotNetMethodDeclaration;
import consulo.dotnet.psi.DotNetQualifiedElement;
import consulo.dotnet.psi.DotNetTypeDeclaration;
import consulo.language.psi.PsiElement;
import consulo.logging.Logger;
import consulo.util.collection.ContainerUtil;
import consulo.util.jdom.JDOMUtil;
import consulo.util.lang.Comparing;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import org.emonic.base.documentation.IDocumentation;
import org.emonic.base.documentation.ITypeDocumentation;
import org.emonic.monodoc.MonodocTree;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author VISTALL
 * @since 13.05.14
 */
@ExtensionImpl
public class MonoDocumentationResolver implements DotNetDocumentationResolver
{
	private static final Logger LOGGER = Logger.getInstance(MonoDocumentationResolver.class);

	private Map<VirtualFile, MonodocTree[]> myCache = ContainerUtil.createConcurrentWeakMap();

	@RequiredReadAction
	@Nullable
	@Override
	public IDocumentation resolveDocumentation(@Nonnull List<VirtualFile> orderEntryFiles, @Nonnull PsiElement element)
	{
		for(VirtualFile orderEntryFile : orderEntryFiles)
		{
			IDocumentation iDocumentation = resolveDocumentation(orderEntryFile, element);
			if(iDocumentation != null)
			{
				return iDocumentation;
			}
		}
		return null;
	}

	@Nullable
	@RequiredReadAction
	private IDocumentation resolveDocumentation(@Nonnull VirtualFile virtualFile, @Nonnull PsiElement element)
	{
		if(!Comparing.equal(virtualFile.getExtension(), "source"))
		{
			return null;
		}
		MonodocTree[] trees = myCache.get(virtualFile);
		if(trees == null)
		{
			trees = loadTrees(virtualFile);
			if(trees.length == 0)
			{
				return null;
			}
			myCache.put(virtualFile, trees);
		}

		String namespace = null;
		String className = null;
		String memberName = null;
		// FIXME [VISTALL] nested types?
		if(element instanceof DotNetTypeDeclaration)
		{
			namespace = ((DotNetTypeDeclaration) element).getPresentableParentQName();
			className = ((DotNetTypeDeclaration) element).getName();
		}
		else if(element instanceof DotNetQualifiedElement)
		{
			if(element instanceof DotNetMethodDeclaration)
			{
				namespace = ((DotNetMethodDeclaration) element).getPresentableParentQName();
				className = ((DotNetMethodDeclaration) element).getName();
			}
		}

		if(className == null)
		{
			return null;
		}

		for(MonodocTree tree : trees)
		{
			ITypeDocumentation documentation = tree.findDocumentation(namespace, className);
			if(documentation != null)
			{
				return documentation;
			}
		}
		return null;
	}

	private MonodocTree[] loadTrees(VirtualFile virtualFile)
	{
		List<MonodocTree> trees = new ArrayList<MonodocTree>(2);
		try
		{
			Document document = JDOMUtil.loadDocument(virtualFile.getInputStream());
			for(Element o : document.getRootElement().getChildren("source"))
			{
				String basefile = o.getAttributeValue("basefile");
				if(basefile == null)
				{
					continue;
				}
				VirtualFile zipFile = virtualFile.getParent().findChild(basefile + ".zip");
				VirtualFile treeFile = virtualFile.getParent().findChild(basefile + ".tree");
				if(zipFile == null || treeFile == null)
				{
					continue;
				}
				MonodocTree tree = new MonodocTree(VirtualFileUtil.virtualToIoFile(treeFile), VirtualFileUtil.virtualToIoFile(zipFile));

				tree.loadNode();

				trees.add(tree);
			}
		}
		catch(JDOMException | IOException e)
		{
			LOGGER.error(e);
		}
		return trees.toArray(new MonodocTree[trees.size()]);
	}
}
