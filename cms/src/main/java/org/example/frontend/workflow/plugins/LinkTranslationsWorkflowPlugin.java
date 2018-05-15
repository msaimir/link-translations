package org.example.frontend.workflow.plugins;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;

import org.apache.commons.lang.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.MarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.panel.EmptyPanel;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.markup.repeater.data.IDataProvider;
import org.apache.wicket.model.IChainingModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.StringResourceModel;
import org.apache.wicket.request.resource.ResourceReference;
import org.hippoecm.addon.workflow.MenuDescription;
import org.hippoecm.addon.workflow.StdWorkflow;
import org.hippoecm.addon.workflow.WorkflowDescriptorModel;
import org.hippoecm.frontend.dialog.AbstractDialog;
import org.hippoecm.frontend.dialog.IDialogService.Dialog;
import org.hippoecm.frontend.editor.plugins.linkpicker.LinkPickerDialog;
import org.hippoecm.frontend.model.JcrNodeModel;
import org.hippoecm.frontend.plugin.IPluginContext;
import org.hippoecm.frontend.plugin.config.IPluginConfig;
import org.hippoecm.frontend.plugin.config.impl.JavaPluginConfig;
import org.hippoecm.frontend.plugins.standards.icon.HippoIcon;
import org.hippoecm.frontend.plugins.standards.icon.HippoIconStack;
import org.hippoecm.frontend.plugins.standards.picker.NodePickerControllerSettings;
import org.hippoecm.frontend.service.IconSize;
import org.hippoecm.frontend.service.render.RenderPlugin;
import org.hippoecm.frontend.session.UserSession;
import org.hippoecm.frontend.skin.CmsIcon;
import org.hippoecm.frontend.skin.Icon;
import org.hippoecm.frontend.translation.DocumentTranslationProvider;
import org.hippoecm.frontend.translation.ILocaleProvider;
import org.hippoecm.frontend.translation.ILocaleProvider.HippoLocale;
import org.hippoecm.frontend.translation.ILocaleProvider.LocaleState;
import org.hippoecm.frontend.translation.TranslationUtil;
import org.hippoecm.repository.api.WorkflowDescriptor;
import org.hippoecm.repository.api.WorkflowException;
import org.hippoecm.repository.api.WorkflowManager;
import org.hippoecm.repository.translation.HippoTranslatedNode;
import org.hippoecm.repository.translation.HippoTranslationNodeType;
import org.hippoecm.repository.translation.TranslationWorkflow;
import org.hippoecm.repository.util.JcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinkTranslationsWorkflowPlugin extends RenderPlugin {

    private static final long serialVersionUID = 1L;
    private static Logger log = LoggerFactory.getLogger(LinkTranslationsWorkflowPlugin.class);
    private static final String CLUSTER_NAME = "cluster.name";
    private static final String LINKPICKER_CLUSTER_NAME= "linkpicker.cluster.name";
    private static final String DEFAULT_CLUSTER = "cms-pickers/documents-only";


    private final IModel<Boolean> canTranslateModel;

    private final DocumentTranslationProvider translationProvider;

    public LinkTranslationsWorkflowPlugin(IPluginContext context, IPluginConfig config) {
        super(context, config);

        final IModel<String> languageModel = new LanguageModel();
        final ILocaleProvider localeProvider = getLocaleProvider();

        Node documentNode = null;
        DocumentTranslationProvider docTranslationProvider = null;
        try {
            documentNode = getDocumentNode();
            docTranslationProvider = new DocumentTranslationProvider(new JcrNodeModel(documentNode),
                    localeProvider);
        } catch (RepositoryException e) {
            log.warn("Unable to find document node", e);
        }
        translationProvider = docTranslationProvider;

        // lazily determine whether the document can be translated
        canTranslateModel = new LoadableDetachableModel<Boolean>() {
            @Override
            protected Boolean load() {
                WorkflowDescriptor descriptor = getModelObject();
                if (descriptor != null) {
                    try {
                        Map<String, Serializable> hints = descriptor.hints();
                        if (hints.containsKey("addTranslation") && hints.get("addTranslation").equals(Boolean.FALSE)) {
                            return false;
                        }
                    } catch (RepositoryException e) {
                        log.error("Failed to analyze hints for translations workflow", e);
                    }
                }
                return true;
            }
        };

        try {
            if (!TranslationUtil.isNtTranslated(documentNode.getParent().getParent()) &&
                    (!TranslationUtil.isNtTranslated(documentNode) || !localeProvider.isKnown(languageModel.getObject()))) {
                return;
            }
        } catch (RepositoryException e) {
            log.warn("Could not determine translations status of document", e);
        }

        add(new EmptyPanel("content"));

        if (!getLanguagesToTranslate().isEmpty()) {

            add(new MenuDescription() {
                private static final long serialVersionUID = 1L;

                @Override
                public Component getLabel() {
                    Fragment fragment = new Fragment("label", "label", LinkTranslationsWorkflowPlugin.this);
                    fragment.add(HippoIcon.fromSprite("menu-image", Icon.TRANSLATE));
                    StringResourceModel title = new StringResourceModel("plugin.menuitem.title", this, null);
                    fragment.add(new Label("menu-title", title));
                    this.setVisible(false);
                    return fragment;
                }

                @Override
                public MarkupContainer getContent() {

                    DataView<HippoLocale> dataView = new DataView<HippoLocale>("languages", new LocalesToTranslateProvider(localeProvider)) {
                        private static final long serialVersionUID = 1L;

                        {
                            onPopulate();
                        }

                        @Override
                        protected void populateItem(Item<HippoLocale> item) {
                            final HippoLocale locale = item.getModelObject();
                            final String language = locale.getName();

                            if (!hasLocale(language)) {
                                item.add(new TranslationAction("language", new LoadableDetachableModel<String>() {

                                    @Override
                                    protected String load() {
                                        return locale.getDisplayName(getLocale()) + "...";
                                    }

                                }, item.getModel(), language
                                ));
                            }
                        }

                        @Override
                        protected void onDetach() {
                            languageModel.detach();
                            super.onDetach();
                        }
                    };
                    Fragment fragment = new Fragment("content", "languages", LinkTranslationsWorkflowPlugin.this);

                    fragment.add(dataView);
                    LinkTranslationsWorkflowPlugin.this.addOrReplace(fragment);

                    return fragment;
                }
            });
        }
    }

    public boolean hasLocale(String locale) {
        return translationProvider.contains(locale);
    }

    @SuppressWarnings("unchecked")
    private Set<String> getLanguagesToTranslate() {
        WorkflowDescriptorModel wdm = (WorkflowDescriptorModel) LinkTranslationsWorkflowPlugin.this.getDefaultModel();
        if (wdm != null) {
            WorkflowDescriptor descriptor = wdm.getObject();
            WorkflowManager manager = UserSession.get().getWorkflowManager();
            try {
                TranslationWorkflow translationWorkflow = (TranslationWorkflow) manager.getWorkflow(descriptor);
                Set<String> available = (Set<String>) translationWorkflow.hints().get("available");
                return available.stream().filter(language -> !hasLocale(language)).collect(Collectors.toSet());
            } catch (RepositoryException | RemoteException | WorkflowException ex) {
                log.error("Failed to retrieve available languages", ex);
            }
        }
        return Collections.emptySet();
    }

    private Node getDocumentNode() throws RepositoryException {
        if (getDefaultModel() instanceof WorkflowDescriptorModel) {
            return ((WorkflowDescriptorModel) getDefaultModel()).getNode();
        }
        return null;
    }

    @Override
    public WorkflowDescriptor getModelObject() {
        if (getDefaultModel() instanceof WorkflowDescriptorModel) {
            return ( (WorkflowDescriptorModel) getDefaultModel()).getObject();
        }
        return null;
    }

    protected ILocaleProvider getLocaleProvider() {
        return getPluginContext().getService(
                getPluginConfig().getString(ILocaleProvider.SERVICE_ID, ILocaleProvider.class.getName()),
                ILocaleProvider.class);
    }

    @Override
    protected void onDetach() {
        if (translationProvider != null) {
            translationProvider.detach();
        }
        this.canTranslateModel.detach();
        super.onDetach();
    }

    private final class LanguageModel extends LoadableDetachableModel<String> {
        private static final long serialVersionUID = 1L;

        @Override
        protected String load() {
            if (LinkTranslationsWorkflowPlugin.this.getDefaultModel() instanceof WorkflowDescriptorModel) {
                WorkflowDescriptorModel wdm = (WorkflowDescriptorModel) LinkTranslationsWorkflowPlugin.this.getDefaultModel();
                try {
                    Node documentNode = wdm.getNode();
                    return documentNode.getProperty(HippoTranslationNodeType.LOCALE).getString();
                } catch (RepositoryException ex) {
                    log.error("failed to load document locale from workflow model", ex);
                }
            }
            return "unknown";
        }
    }

    private final class LocalesToTranslateProvider implements IDataProvider<HippoLocale> {
        private final ILocaleProvider localeProvider;
        private static final long serialVersionUID = 1L;
        private transient List<HippoLocale> availableLocales;

        private LocalesToTranslateProvider(ILocaleProvider localeProvider) {
            this.localeProvider = localeProvider;
        }

        private void load() {
            availableLocales = new LinkedList<>();
            for (String language : getLanguagesToTranslate()) {
                availableLocales.add(localeProvider.getLocale(language));
            }
            Collections.sort(availableLocales, Comparator.comparing(o -> o.getDisplayName(getLocale())));
        }

        @Override
        public Iterator<? extends HippoLocale> iterator(long first, long count) {
            if (availableLocales == null) {
                load();
            }
            return availableLocales.subList((int) first, (int)(first + count)).iterator();
        }

        @Override
        public IModel<HippoLocale> model(HippoLocale object) {
            final String id = object.getName();
            return new LoadableDetachableModel<HippoLocale>() {
                private static final long serialVersionUID = 1L;

                @Override
                protected HippoLocale load() {
                    return localeProvider.getLocale(id);
                }

            };
        }

        @Override
        public long size() {
            if (availableLocales == null) {
                load();
            }
            return availableLocales.size();
        }

        public void detach() {
            availableLocales = null;
        }
    }

    private final class TranslationAction extends StdWorkflow<TranslationWorkflow> {
        private static final long serialVersionUID = 1L;

        private final String language;

        private final IModel<HippoLocale> localeModel;

        private final IModel<String> title;

        private TranslationAction(String id, IModel<String> name, IModel<HippoLocale> localeModel, String language) {
            super(id, name, getPluginContext(), (WorkflowDescriptorModel) LinkTranslationsWorkflowPlugin.this.getModel());
            this.language = language;
            this.title = name;
            this.localeModel = localeModel;
        }

        @Override
        public boolean isVisible() {
            if (super.isVisible() && findPage() != null) {
                return canTranslateModel.getObject();
            }
            return false;
        }

        @Override
        protected Component getIcon(final String id) {
            final HippoLocale hippoLocale = localeModel.getObject();
            final HippoIconStack nodeIcon = new HippoIconStack(id, IconSize.M);

            final ResourceReference flagIcon = hippoLocale.getIcon(IconSize.M, LocaleState.EXISTS);
            nodeIcon.addFromResource(flagIcon);

            if (!hasLocale(hippoLocale.getName())) {
                nodeIcon.addFromCms(CmsIcon.OVERLAY_PLUS, IconSize.M, HippoIconStack.Position.TOP_LEFT);
            }

            return nodeIcon;
        }

        @Override
        protected IModel<String> getTitle() {
            return title;
        }

        @Override
        protected Dialog createRequestDialog() {
            if (hasLocale(language)) {
                return null;
            }
            try {
                return createLinkPickerDialog(getPluginContext());
            } catch (RepositoryException e) {
                log.error("cannot create linkpicker dialog",e);
            }
            return null;
        }

        @Override
        protected void onDetach() {
            super.onDetach();
        }
        /**
         * Create a link picker dialog
         */
        private AbstractDialog<String> createLinkPickerDialog(final IPluginContext context) throws RepositoryException {

            Node documentVariantNode = getModel().getNode();
            HippoTranslatedNode translatedDocNode = new HippoTranslatedNode(documentVariantNode);
            HippoTranslatedNode closestTranslatedFolder = getClosestFolderWithLinkedTranslations(translatedDocNode);

            final IPluginConfig dialogConfig = fromWorkflowDescriptorModel(getPluginConfig(), getModel());

            if (closestTranslatedFolder != null) {
                dialogConfig.put(NodePickerControllerSettings.BASE_UUID, closestTranslatedFolder.getTranslation(language).getIdentifier());
            }

            final IChainingModel<String> linkPickerModel = new IChainingModel<String>() {

                private String object;
                private IModel<?> model;

                @Override
                public void detach() {
                    object = null;
                }

                @Override
                public String getObject() {
                    return object;
                }

                @Override
                public void setObject(final String object) {
                    this.object = object;
                    updateTranslations(object);
                    redraw();
                }

                @Override
                public void setChainedModel(final IModel<?> model) {
                    this.model = model;
                }

                @Override
                public IModel<?> getChainedModel() {
                    return model;
                }

                private void updateTranslations(final String uuid)  {
                    javax.jcr.Session session = UserSession.get().getJcrSession();
                    try {
                        Node selectedDocumentNodeHandle = session.getNodeByIdentifier(uuid);
                        if (canUpdateTranslation(selectedDocumentNodeHandle)) {
                            Node currentDocumentNodeVariant = getDocumentNode();
                            String translationId = currentDocumentNodeVariant.getProperty(HippoTranslationNodeType.ID).getString();
                            log.debug("link translations of {} with {}", selectedDocumentNodeHandle.getPath(), currentDocumentNodeVariant.getPath());
                            setTranslationId(selectedDocumentNodeHandle, translationId);
                        } else {
                            log.warn("cannot link translations between {} and {}",selectedDocumentNodeHandle.getPath(), getDocumentNode().getPath());
                        }
                    } catch (RepositoryException e) {
                        log.error("Error linking translation with "+uuid, e);
                    }
                }

                private void setTranslationId(Node handleNode, String translationId) {
                    if (handleNode != null) {
                        try {
                            NodeIterator docNodes = handleNode.getNodes(handleNode.getName());
                            while (docNodes.hasNext()) {
                                Node docNode = docNodes.nextNode();
                                log.debug("Setting translationID of " + docNode.getPath() + " to " + translationId);
                                JcrUtils.ensureIsCheckedOut(docNode);
                                docNode.setProperty(HippoTranslationNodeType.ID, translationId);
                                docNode.getSession().save();
                                docNode.getSession().refresh(false);
                            }
                        } catch (RepositoryException e) {
                            log.error("could not set property hippotranslation:id for document "
                                    + new JcrNodeModel(handleNode).getItemModel().getPath(), e);
                        }
                    }
                }

                private boolean canUpdateTranslation(final Node selectedDocumentHandle) throws RepositoryException {
                    Node selectedDocumentVariant = selectedDocumentHandle.getNode(selectedDocumentHandle.getName());
                    HippoTranslatedNode selectedDocumentTranslatedNode = new HippoTranslatedNode(selectedDocumentVariant);

                    return
                            (   closestTranslatedFolder == null ||
                                    StringUtils.startsWith(selectedDocumentVariant.getPath(), closestTranslatedFolder.getTranslation(language).getPath())
                            )
                                    && language.equals(selectedDocumentTranslatedNode.getLocale());
                }
            };
            return new LinkPickerDialog(context, dialogConfig, linkPickerModel);

        }


        private IPluginConfig fromWorkflowDescriptorModel(final IPluginConfig pluginConfig, final WorkflowDescriptorModel workflowDescriptorModel) throws RepositoryException {
            IPluginConfig mergedPluginConfig = new JavaPluginConfig(pluginConfig);
            mergedPluginConfig.put(NodePickerControllerSettings.SELECTABLE_NODETYPES, workflowDescriptorModel.getNode().getPrimaryNodeType().getName());
            mergedPluginConfig.put(CLUSTER_NAME, pluginConfig.getString(LINKPICKER_CLUSTER_NAME, DEFAULT_CLUSTER));
            return mergedPluginConfig;
        }

        private HippoTranslatedNode getClosestFolderWithLinkedTranslations(final HippoTranslatedNode translatedNode) throws RepositoryException {
            if (translatedNode == null) {
                return null;
            }
            if (translatedNode.hasTranslation(language)){
                return new HippoTranslatedNode(translatedNode.getTranslation(language));
            } else {
                Node parentFolder = translatedNode.getContainingFolder();
                if (parentFolder.isNodeType(HippoTranslationNodeType.NT_TRANSLATED)) {
                    return getClosestFolderWithLinkedTranslations(new HippoTranslatedNode(parentFolder));
                } else {
                    return null;
                }
            }
        }

    }

}
