/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.sheet.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.xwiki.bridge.DocumentModelBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.sheet.SheetManager;
import org.xwiki.sheet.SheetManagerConfiguration;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Default {@link SheetManager} implementation.
 * 
 * @version $Id$
 */
@Component
public class DefaultSheetManager implements SheetManager
{
    /**
     * The name of the xclass that describes a sheet.
     */
    private static final String SHEET_CLASS = "SheetClass";

    /**
     * The name of the xclass that is used to bind a xclass to a sheet.
     */
    private static final String CLASS_SHEET_BINDING = "ClassSheetBinding";

    /**
     * The name of the xclass that is used to apply a sheet to a document.
     */
    private static final String DOCUMENT_SHEET_BINDING = "DocumentSheetBinding";

    /**
     * The property of {@link #CLASS_SHEET_BINDING} and {@link #DOCUMENT_SHEET_BINDING} classes that specifies the
     * sheet.
     */
    private static final String SHEET_PROPERTY = "sheet";

    /**
     * Execution context handler, needed for accessing the XWikiContext.
     */
    @Inject
    private Execution execution;

    /**
     * The component used to resolve a string document reference.
     */
    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    /**
     * The component used to access the configuration parameters.
     */
    @Inject
    private SheetManagerConfiguration configuration;

    @Override
    public List<DocumentReference> getSheets(DocumentModelBridge document, String action, SheetDisplay display)
    {
        DocumentReference documentReference = document.getDocumentReference();

        // (1) Check if there is a sheet specified for the current request.
        String sheetStringRef = getXWikiContext().getRequest().getParameter(SHEET_PROPERTY);
        if (sheetStringRef != null) {
            DocumentReference sheetReference = documentReferenceResolver.resolve(sheetStringRef, documentReference);
            if (matchSheet(sheetReference, action, display)) {
                return Collections.singletonList(sheetReference);
            }
        }

        // (2) Look for custom sheets.
        List<DocumentReference> sheets = new ArrayList<DocumentReference>(getCustomSheets(document, action, display));
        if (!sheets.isEmpty()) {
            return sheets;
        }

        // (3) Look for class sheets.
        for (DocumentReference classReference : getDocument(document).getXObjects().keySet()) {
            DocumentReference sheetReference = getClassSheet(classReference, action);
            if (sheetReference != null && matchSheet(sheetReference, null, display)) {
                sheets.add(sheetReference);
            }
        }

        // (4) If the specified document holds a class definition and it doesn't have any sheets (neither included nor
        // binded) then use the default class sheet.
        if (sheets.isEmpty() && holdsClassDefinition(document)) {
            DocumentReference defaultClassSheet =
                documentReferenceResolver.resolve(configuration.getDefaultClassSheet(), documentReference);
            if (matchSheet(defaultClassSheet, action, display)) {
                sheets.add(defaultClassSheet);
            }
        }

        return sheets;
    }

    @Override
    public DocumentReference getClassSheet(DocumentReference classReference, String action)
    {
        XWikiContext context = getXWikiContext();

        // (1) Look for explicitly binded sheets.
        String wikiName = classReference.getWikiReference().getName();
        DocumentReference classSheetBindingReference =
            new DocumentReference(wikiName, XWiki.SYSTEM_SPACE, CLASS_SHEET_BINDING);
        XWikiDocument classDocument;
        try {
            classDocument = context.getWiki().getDocument(classReference, context);
        } catch (XWikiException e) {
            throw new RuntimeException(e);
        }
        List<BaseObject> classSheetBindingObjects = classDocument.getXObjects(classSheetBindingReference);
        if (classSheetBindingObjects != null) {
            for (BaseObject classSheetBindingObject : classSheetBindingObjects) {
                String sheetStringRef = classSheetBindingObject.getStringValue(SHEET_PROPERTY);
                DocumentReference sheetReference = documentReferenceResolver.resolve(sheetStringRef, classReference);
                if (matchSheet(sheetReference, action, null)) {
                    return sheetReference;
                }
            }
        }

        // (2) Follow naming convention: <ClassName><ActionName>Sheet
        String className = StringUtils.chomp(classReference.getName(), "Class");
        DocumentReference sheetReference = new DocumentReference(classReference.clone());
        String actionName = action == null ? "" : StringUtils.capitalize(action);
        sheetReference.setName(String.format("%s%sSheet", className, actionName));
        if (context.getWiki().exists(sheetReference, context)) {
            return sheetReference;
        }

        // (3) Follow naming convention: <ClassName>Sheet
        sheetReference.setName(className + "Sheet");
        if (matchSheet(sheetReference, action, null)) {
            return sheetReference;
        }

        // (4) Fall-back on default class sheet bindings.
        sheetReference = getDefaultClassSheet(classReference);
        if (sheetReference != null && matchSheet(sheetReference, action, null)) {
            return sheetReference;
        }

        return null;
    }

    /**
     * @return the XWiki context
     * @deprecated avoid using this method; try using the document access bridge instead
     */
    private XWikiContext getXWikiContext()
    {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
    }

    /**
     * @param document the document where to look for sheet references
     * @param action the action for which to retrieve the sheets
     * @param display the expected value of the sheet display
     * @return the list of sheets that are referenced by the given document and which satisfy the constraints (action
     *         and display)
     */
    private List<DocumentReference> getCustomSheets(DocumentModelBridge document, String action, SheetDisplay display)
    {
        String wikiName = document.getDocumentReference().getWikiReference().getName();
        DocumentReference docSheetBindingReference =
            new DocumentReference(wikiName, XWiki.SYSTEM_SPACE, DOCUMENT_SHEET_BINDING);

        List<BaseObject> docSheetBindingObjects = getDocument(document).getXObjects(docSheetBindingReference);
        if (docSheetBindingObjects == null) {
            return Collections.emptyList();
        }

        List<DocumentReference> sheets = new ArrayList<DocumentReference>();
        for (BaseObject docSheetBindingObject : docSheetBindingObjects) {
            String sheetStringRef = docSheetBindingObject.getStringValue(SHEET_PROPERTY);
            DocumentReference sheetReference =
                documentReferenceResolver.resolve(sheetStringRef, document.getDocumentReference());
            if (matchSheet(sheetReference, action, display)) {
                sheets.add(sheetReference);
            }
        }
        return sheets;
    }

    /**
     * @param sheetReference specifies the sheet that is matched
     * @param action the expected value of the action sheet property
     * @param display the expected value of the display sheet property
     * @return {@code true} if the given document reference points to a sheet that matches the action and display
     *         constraints
     */
    private boolean matchSheet(DocumentReference sheetReference, String action, SheetDisplay display)
    {
        XWikiContext context = getXWikiContext();
        if (!context.getWiki().exists(sheetReference, context)) {
            return false;
        }

        String wikiName = sheetReference.getWikiReference().getName();
        DocumentReference sheetClassReference = new DocumentReference(wikiName, XWiki.SYSTEM_SPACE, SHEET_CLASS);

        XWikiDocument sheetDocument;
        try {
            sheetDocument = context.getWiki().getDocument(sheetReference, context);
        } catch (XWikiException e) {
            throw new RuntimeException(e);
        }
        BaseObject sheetObject = sheetDocument.getXObject(sheetClassReference);

        return matchesDisplay(sheetObject, display) && matchesAction(sheetObject, action);
    }

    /**
     * @param sheetObject the object describing the sheet
     * @param expectedDisplay the expected value of the {@code display} property
     * @return {@code true} if the display property of the given sheet object matches the given value, {@code false}
     *         otherwise
     */
    private boolean matchesDisplay(BaseObject sheetObject, SheetDisplay expectedDisplay)
    {
        // We assume the display is in-line if the sheet object is not present (is null).
        // We assume any display value is good if the expected value is not specified (is null).
        return expectedDisplay == null
            || (sheetObject == null ? expectedDisplay == SheetDisplay.INLINE : expectedDisplay.toString()
                .equalsIgnoreCase(sheetObject.getStringValue("display")));
    }

    /**
     * @param sheetObject the object describing the sheet
     * @param expectedAction the expected value of the {@code action} property
     * @return {@code true} if the action property of the given sheet object matches the given value, {@code false}
     *         otherwise
     */
    private boolean matchesAction(BaseObject sheetObject, String expectedAction)
    {
        // We assume the sheet matches all actions is the sheet object is not present (is null).
        // We assume all actions are matched if the expected value is not specified (is empty).
        String actualAction = sheetObject == null ? null : sheetObject.getStringValue("action");
        return StringUtils.isEmpty(expectedAction) || StringUtils.isEmpty(actualAction)
            || actualAction.equals(expectedAction);
    }

    /**
     * @param document a XWiki document
     * @return {@code true} if the specified document holds a class definition, {@code false} otherwise
     */
    private boolean holdsClassDefinition(DocumentModelBridge document)
    {
        return !getDocument(document).getXClass().getEnabledProperties().isEmpty();
    }

    /**
     * @param classReference a reference to a document holding a class definition
     * @return the default sheet for the specified class, read from the configuration (not from the objects attached to
     *         the class document, not following naming conventions)
     */
    private DocumentReference getDefaultClassSheet(DocumentReference classReference)
    {
        Properties defaultClassSheetBindings = configuration.getDefaultClassSheetBinding();
        for (Entry<Object, Object> binding : defaultClassSheetBindings.entrySet()) {
            DocumentReference reference =
                documentReferenceResolver.resolve(String.valueOf(binding.getKey()), classReference);
            if (classReference.equals(reference)) {
                return documentReferenceResolver.resolve(String.valueOf(binding.getValue()), classReference);
            }
        }
        return null;
    }

    /**
     * @param document a {@link DocumentModelBridge} instance
     * @return the XWiki document object wrapped by the given {@link DocumentModelBridge} instance
     * @deprecated avoid using this method as much as possible; use the bridge methods instead
     */
    private XWikiDocument getDocument(DocumentModelBridge document)
    {
        return (XWikiDocument) document;
    }
}
