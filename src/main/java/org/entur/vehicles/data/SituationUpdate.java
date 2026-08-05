package org.entur.vehicles.data;

import org.entur.vehicles.data.model.Affects;
import org.entur.vehicles.data.model.Codespace;
import org.entur.vehicles.data.model.InfoLink;
import org.entur.vehicles.data.model.TranslatedString;
import org.entur.vehicles.data.model.ValidityPeriod;
import org.springframework.graphql.data.method.annotation.SchemaMapping;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * A SIRI-SX situation.
 * <p>
 * Deliberately does not extend {@code AbstractUpdate}: that base models a single
 * line, operator and service journey, whereas a situation affects many of each.
 * <p>
 * A null {@link #getExpiration()} means the situation never expires. Situations
 * published without a validity end time are retained indefinitely so that
 * producers who never close them can be identified.
 */
@SchemaMapping
public class SituationUpdate {

    private String situationNumber;
    private String participantRef;
    private Codespace codespace;
    private Integer version;
    private String sourceType;
    private WorkflowStatusEnumeration progress;
    private SeverityEnumeration severity;
    private Integer priority;
    private String reportType;
    private List<String> keywords;
    private Boolean planned;
    private ZonedDateTime creationTime;
    private ZonedDateTime versionedAtTime;
    private List<ValidityPeriod> validityPeriods;
    private List<TranslatedString> summary;
    private List<TranslatedString> description;
    private List<TranslatedString> advice;
    private List<TranslatedString> detail;
    private List<InfoLink> infoLinks;
    private Affects affects;
    private ZonedDateTime lastUpdated;
    private ZonedDateTime expiration;

    public String getSituationNumber() {
        return situationNumber;
    }

    public void setSituationNumber(String situationNumber) {
        this.situationNumber = situationNumber;
    }

    public String getParticipantRef() {
        return participantRef;
    }

    public void setParticipantRef(String participantRef) {
        this.participantRef = participantRef;
    }

    public Codespace getCodespace() {
        return codespace;
    }

    public void setCodespace(Codespace codespace) {
        this.codespace = codespace;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public WorkflowStatusEnumeration getProgress() {
        return progress;
    }

    public void setProgress(WorkflowStatusEnumeration progress) {
        this.progress = progress;
    }

    public SeverityEnumeration getSeverity() {
        return severity;
    }

    public void setSeverity(SeverityEnumeration severity) {
        this.severity = severity;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public Boolean getPlanned() {
        return planned;
    }

    public void setPlanned(Boolean planned) {
        this.planned = planned;
    }

    public ZonedDateTime getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(ZonedDateTime creationTime) {
        this.creationTime = creationTime;
    }

    public ZonedDateTime getVersionedAtTime() {
        return versionedAtTime;
    }

    public void setVersionedAtTime(ZonedDateTime versionedAtTime) {
        this.versionedAtTime = versionedAtTime;
    }

    public List<ValidityPeriod> getValidityPeriods() {
        return validityPeriods;
    }

    public void setValidityPeriods(List<ValidityPeriod> validityPeriods) {
        this.validityPeriods = validityPeriods;
    }

    public List<TranslatedString> getSummary() {
        return summary;
    }

    public void setSummary(List<TranslatedString> summary) {
        this.summary = summary;
    }

    public List<TranslatedString> getDescription() {
        return description;
    }

    public void setDescription(List<TranslatedString> description) {
        this.description = description;
    }

    public List<TranslatedString> getAdvice() {
        return advice;
    }

    public void setAdvice(List<TranslatedString> advice) {
        this.advice = advice;
    }

    public List<TranslatedString> getDetail() {
        return detail;
    }

    public void setDetail(List<TranslatedString> detail) {
        this.detail = detail;
    }

    public List<InfoLink> getInfoLinks() {
        return infoLinks;
    }

    public void setInfoLinks(List<InfoLink> infoLinks) {
        this.infoLinks = infoLinks;
    }

    public Affects getAffects() {
        return affects;
    }

    public void setAffects(Affects affects) {
        this.affects = affects;
    }

    public ZonedDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(ZonedDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Long getLastUpdatedEpochSecond() {
        return lastUpdated != null ? lastUpdated.toEpochSecond() : null;
    }

    /** Null means the situation never expires. */
    public ZonedDateTime getExpiration() {
        return expiration;
    }

    public void setExpiration(ZonedDateTime expiration) {
        this.expiration = expiration;
    }

    public Long getExpirationEpochSecond() {
        return expiration != null ? expiration.toEpochSecond() : null;
    }

    /** True when no validity period carries an end time. */
    public Boolean getOpenEnded() {
        if (validityPeriods == null || validityPeriods.isEmpty()) {
            return true;
        }
        return validityPeriods.stream().allMatch(ValidityPeriod::isOpenEnded);
    }

    /** Time elapsed since creationTime; null when creationTime is absent. */
    public Duration getAge() {
        return creationTime != null ? Duration.between(creationTime, ZonedDateTime.now()) : null;
    }

    public boolean isValidAt(ZonedDateTime timestamp) {
        if (validityPeriods == null || validityPeriods.isEmpty()) {
            return true;
        }
        return validityPeriods.stream().anyMatch(period -> period.isValidAt(timestamp));
    }
}
