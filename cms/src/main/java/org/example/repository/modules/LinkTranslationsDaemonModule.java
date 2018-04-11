package org.example.repository.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.hippoecm.repository.HippoStdNodeType;
import org.hippoecm.repository.api.HippoNode;
import org.hippoecm.repository.util.JcrUtils;
import org.hippoecm.repository.util.NodeIterable;
import org.onehippo.cms7.services.HippoServiceRegistry;
import org.onehippo.cms7.services.eventbus.HippoEventBus;
import org.onehippo.cms7.services.eventbus.Subscribe;
import org.onehippo.repository.events.HippoWorkflowEvent;
import org.onehippo.repository.modules.AbstractReconfigurableDaemonModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinkTranslationsDaemonModule extends AbstractReconfigurableDaemonModule {

    private static final Logger log = LoggerFactory.getLogger(LinkTranslationsDaemonModule.class);
    private static final String ENABLED = "enabled";
    private static final String DOCUMENT_TYPE = "documentType";

    private static Map<String, String> locales = new HashMap<>();
    private Set<String> documentTypes = new HashSet<>();
    private boolean enabled = false;
    static {
        locales.put("en","nl");
        locales.put("nl", "en");


    }
    private Session session;

    @Override
    protected void doConfigure(final Node moduleConfig) throws RepositoryException {
        log.debug("(re)configure daemon module");
        enabled = JcrUtils.getBooleanProperty(moduleConfig, ENABLED, false);
        String [] documentTypesArray = JcrUtils.getMultipleStringProperty(moduleConfig, DOCUMENT_TYPE, null);
        this.documentTypes.clear();
        if (documentTypesArray != null) {
            for (String docType : documentTypesArray) {
                documentTypes.add(docType);
            }
        }
    }

    @Override
    protected void doInitialize(final Session session) throws RepositoryException {
        log.debug("initialize daemon module");
        this.session = session;
        HippoServiceRegistry.registerService(this, HippoEventBus.class);
    }

    @Override
    protected void doShutdown() {
        log.debug("shutdown daemon module");
        HippoServiceRegistry.unregisterService(this, HippoEventBus.class);
    }

    @Subscribe
    public void handleEvent(final HippoWorkflowEvent event) throws RepositoryException {

        if (enabled && event.success() && documentTypes.contains(event.documentType()) && "commitEditableInstance".equals(event.action())) {
            log.debug("event interaction {}", event.interaction());
            final HippoNode handle = (HippoNode) session.getNodeByIdentifier(event.subjectId());
            Node draftVariant = getVariant(handle, HippoStdNodeType.DRAFT);
            if (draftVariant != null && draftVariant.hasProperty("hippotranslation:id") && draftVariant.hasProperty("hippotranslation:locale") && draftVariant.hasNode("translationspoc:translationlink")) {
                NodeType nodeType = draftVariant.getPrimaryNodeType();
                String translationId = draftVariant.getProperty("hippotranslation:id").getString();
                String locale = draftVariant.getProperty("hippotranslation:locale").getString();
                Node translationLinkNode = draftVariant.getNode("translationspoc:translationlink");

                if (translationLinkNode != null && nodeType != null && translationId != null) {
                    String uuid = translationLinkNode.getProperty("hippo:docbase").getString();
                    Node translatedHandleNode = session.getNodeByIdentifier(uuid);
                    if (translatedHandleNode != null) {
                        log.debug("link translations {} with {}", handle.getPath(), translatedHandleNode.getPath());
                        updateTranslationIdOnVariants(translatedHandleNode, event.subjectId(), nodeType, translationId, locales.get(locale));
                        session.save();
                    }
                }
            }
        }
    }

    private Node getVariant(Node handle, final String state) throws RepositoryException {
        for (Node variant : new NodeIterable(handle.getNodes(handle.getName()))) {
            final String variantState = JcrUtils.getStringProperty(variant, HippoStdNodeType.HIPPOSTD_STATE, null);
            if (state.equals(variantState)) {
                return variant;
            }
        }
        return null;
    }

    private void updateTranslationIdOnVariants(final Node handle, final String uuid, final NodeType nodeType, final String translationId, final String locale) throws RepositoryException {
        for (Node variant : new NodeIterable(handle.getNodes(handle.getName()))) {
            if (variant.getPrimaryNodeType().equals(nodeType) && variant.isNodeType("hippotranslation:translated")) {
                JcrUtils.ensureIsCheckedOut(variant);
                variant.setProperty("hippotranslation:id", translationId);
                variant.setProperty("hippotranslation:locale", locale);
                if (!variant.hasNode("translationspoc:translationlink")){
                    variant.addNode("translationspoc:translationlink", "hippo:mirror");
                }
                Node translationLinkNode = variant.getNode("translationspoc:translationlink");
                translationLinkNode.setProperty("hippo:docbase", uuid);
            }
        }
    }

}
