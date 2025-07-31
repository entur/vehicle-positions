package org.entur.vehicles.service.graphql;

import org.entur.vehicles.data.model.DatedServiceJourney;
import org.entur.vehicles.data.model.Line;
import org.entur.vehicles.data.model.Operator;
import org.entur.vehicles.data.model.ServiceJourney;

import java.util.List;

public class Data {
    public Line line;
    public List<Line> lines;
    public ServiceJourney serviceJourney;
    public DatedServiceJourney datedServiceJourney;

    public Operator operator;

    public List<Operator> operators;

    Data() { }

    public void setLine(Line line) {
        this.line = line;
    }

    public void setLines(List<Line> lines) {
        this.lines = lines;
    }

    public void setServiceJourney(ServiceJourney serviceJourney) {
        this.serviceJourney = serviceJourney;
    }

    public void setDatedServiceJourney(DatedServiceJourney datedServiceJourney) {
        this.datedServiceJourney = datedServiceJourney;
    }

    public void setOperator(Operator operator) {
        this.operator = operator;
    }

    public void setOperators(List<Operator> operators) {
        this.operators = operators;
    }
}
