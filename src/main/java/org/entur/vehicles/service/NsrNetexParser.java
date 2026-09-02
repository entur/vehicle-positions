package org.entur.vehicles.service;

import org.entur.netex.NetexParser;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.entur.vehicles.data.model.Location;
import org.entur.vehicles.data.model.StopPoint;
import org.rutebanken.netex.model.LocationStructure;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.Quays_RelStructure;
import org.rutebanken.netex.model.SiteRefStructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The JAXB parse of the NSR stop-place export, moved out of {@code NSRService.warmUpCache}
 * unchanged so it is a pure function of the zip. This is the slow path (about 18 s on the
 * full export); the snapshot exists so most pods never run it.
 */
public final class NsrNetexParser {

    public NsrData parse(Path zip) throws IOException {
        NetexEntitiesIndex index;
        try {
            index = new NetexParser().parse(zip.toAbsolutePath().toString());
        } catch (RuntimeException e) {
            throw new IOException("Could not parse NSR export " + zip, e);
        }

        Map<String, StopPoint> stopPoints = new HashMap<>();
        // The parser already publishes quay -> stop place; stop place -> multimodal parent is
        // added from the loop below, which visits every stop place anyway.
        Map<String, String> childToParent = new HashMap<>(index.getStopPlaceIdByQuayIdIndex());

        index.getStopPlaceIndex().getLatestVersions().forEach(stopPlace -> {
            String stopPlaceId = stopPlace.getId();
            SiteRefStructure parentSiteRef = stopPlace.getParentSiteRef();
            if (parentSiteRef != null && parentSiteRef.getRef() != null) {
                childToParent.put(stopPlaceId, parentSiteRef.getRef());
            }
            String stopPlaceName = stopPlace.getName().getValue();
            LocationStructure stopPlaceLocation = stopPlace.getCentroid().getLocation();
            stopPoints.put(stopPlaceId, new StopPoint(stopPlaceId, stopPlaceName,
                    new Location(stopPlaceLocation.getLongitude().doubleValue(), stopPlaceLocation.getLatitude().doubleValue())));
            Quays_RelStructure quays = stopPlace.getQuays();
            if (quays != null) {
                quays.getQuayRefOrQuay().forEach(jaxbQuay -> {
                    if (jaxbQuay.getValue() instanceof Quay quay) {
                        String id = quay.getId();
                        String name = quay.getName() == null || quay.getName().getValue() == null
                                ? stopPlaceName
                                : quay.getName().getValue();
                        LocationStructure quayLocation = quay.getCentroid().getLocation();
                        stopPoints.put(id, new StopPoint(id, name,
                                new Location(quayLocation.getLongitude().doubleValue(), quayLocation.getLatitude().doubleValue())));
                    }
                });
            }
        });
        return new NsrData(stopPoints, childToParent);
    }
}
