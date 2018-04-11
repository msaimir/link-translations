package org.example.beans;

import java.util.Calendar;
import org.hippoecm.hst.content.beans.Node;
import org.hippoecm.hst.content.beans.standard.HippoHtml;
import org.onehippo.cms7.essentials.dashboard.annotations.HippoEssentialsGenerated;
import org.hippoecm.hst.content.beans.standard.HippoBean;

@HippoEssentialsGenerated(internalName = "translationspoc:contentdocument")
@Node(jcrType = "translationspoc:contentdocument")
public class ContentDocument extends BaseDocument {
    @HippoEssentialsGenerated(internalName = "translationspoc:introduction")
    public String getIntroduction() {
        return getProperty("translationspoc:introduction");
    }

    @HippoEssentialsGenerated(internalName = "translationspoc:title")
    public String getTitle() {
        return getProperty("translationspoc:title");
    }

    @HippoEssentialsGenerated(internalName = "translationspoc:content")
    public HippoHtml getContent() {
        return getHippoHtml("translationspoc:content");
    }

    @HippoEssentialsGenerated(internalName = "translationspoc:publicationdate")
    public Calendar getPublicationDate() {
        return getProperty("translationspoc:publicationdate");
    }

    @HippoEssentialsGenerated(internalName = "translationspoc:translationlink")
    public HippoBean getTranslationlink() {
        return getLinkedBean("translationspoc:translationlink", HippoBean.class);
    }
}
