package org.protege.owl.server.configuration.factories;

import static org.protege.owl.server.configuration.MetaprojectVocabulary.HAS_REGISTRY_PORT;
import static org.protege.owl.server.configuration.MetaprojectVocabulary.HAS_SERVER_PORT;
import static org.protege.owl.server.configuration.MetaprojectVocabulary.RMI_TRANSPORT;

import java.rmi.registry.Registry;
import java.util.logging.Logger;

import org.protege.owl.server.api.Server;
import org.protege.owl.server.api.ServerComponentFactory;
import org.protege.owl.server.api.ServerFilter;
import org.protege.owl.server.api.ServerTransport;
import org.protege.owl.server.connect.rmi.RMITransport;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;

public class RMIConnectionFactory implements ServerComponentFactory {
    public static Logger logger = Logger.getLogger(CoreServerFactory.class.getCanonicalName());
    private OWLOntology ontology;
    private OWLDataFactory factory;
    
    @Override
    public void setConfiguration(OWLOntology ontology) {
        this.ontology = ontology;
        factory = ontology.getOWLOntologyManager().getOWLDataFactory();
    }

    @Override
    public boolean hasSuitableServer(OWLIndividual i) {
        return false;
    }

    @Override
    public Server createServer(OWLIndividual i) {
        return null;
    }

    @Override
    public boolean hasSuitableServerFilter(OWLIndividual i) {
        return false;
    }

    @Override
    public ServerFilter createServerFilter(OWLIndividual i, Server server) {
        return null;
    }

    @Override
    public boolean hasSuitableServerTransport(OWLIndividual i) {
        OWLAxiom rightType = factory.getOWLClassAssertionAxiom(RMI_TRANSPORT, i);
        boolean hasRightType = ontology.containsAxiom(rightType);
        return hasRightType && getServerPort(i) != null;
    }

    @Override
    public ServerTransport createServerTransport(OWLIndividual i) {
        return new RMITransport(getRegistryPort(i), getServerPort(i));
    }
    
    private int getRegistryPort(OWLIndividual i) {
        for (OWLLiteral hostPortLiteral : i.getDataPropertyValues(HAS_REGISTRY_PORT, ontology)) {
            if (hostPortLiteral.isInteger()) {
                int port = hostPortLiteral.parseInteger();
                return port;
            }
        }
        return Registry.REGISTRY_PORT;
    }
    
    private Integer getServerPort(OWLIndividual i) {
        for (OWLLiteral hostPortLiteral : i.getDataPropertyValues(HAS_SERVER_PORT, ontology)) {
            if (hostPortLiteral.isInteger()) {
                int port = hostPortLiteral.parseInteger();
                return port;
            }
        }
        return null;
    }
    
    @Override
    public String toString() {
        return "RMI Transport Factory";
    }

}
