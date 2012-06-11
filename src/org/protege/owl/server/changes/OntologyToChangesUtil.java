package org.protege.owl.server.changes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.protege.owl.server.api.OntologyDocumentRevision;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.SetOntologyID;


/**
 * 
 * @author tredmond
 * @deprecated replace with Matthew's binary serialization format.
 */
@Deprecated
public class OntologyToChangesUtil {
	public static List<OWLOntologyChange> getChanges(OWLOntology ontology) {
		return new OntologyToChangesUtil(ontology).getChanges();
	}
	
	private OntologyDocumentRevision startRevision;
	private OWLOntology changesOntology;
	private OWLOntology ontology;
	private Map<Integer, OWLOntologyChange> changeMap = new TreeMap<Integer, OWLOntologyChange>();
	
	
	private OntologyToChangesUtil(OWLOntology ontology) {
		this.ontology = ontology;
	}
	
	
	public List<OWLOntologyChange> getChanges() {
		List<OWLOntologyChange> changeList = new ArrayList<OWLOntologyChange>();
		OWLOntologyChange change;
		int revision = startRevision.getRevision();
		while ((change = changeMap.get(revision++)) != null) {
			changeList.add(change);
		}
		return changeList;
	}


	public void handleAxioms() {
		for (OWLAxiom axiom : changesOntology.getAxioms()) {
			Set<OWLAnnotation> annotations = axiom.getAnnotations();
			OWLAxiom cleanedAxiom = axiom.getAxiomWithoutAnnotations().getAnnotatedAxiom(removeChangeOntologyAnnotations(annotations));
			int revision = getRevision(annotations);
			OWLOntologyChange change;
			if (isAdded(annotations)) {
				change = new AddAxiom(ontology, cleanedAxiom);
			}
			else {
				change = new RemoveAxiom(ontology, cleanedAxiom);
			}
			changeMap.put(revision, change);
		}
	}
	
	public void handleAnnotations() {
		for (OWLAnnotation annotation : changesOntology.getAnnotations()) {
			OWLAnnotationValue rawValue = annotation.getValue();
			int revision = getRevision(annotation.getAnnotations());
			OWLOntologyChange change;
			if (annotation.getProperty().equals(ChangeOntology.SET_ONTOLOGY_ID)) {
				IRI name = (IRI) rawValue;
				IRI version = getVersionIRI(annotation.getAnnotations());
				OWLOntologyID id;
				if (version == null) {
					id = new OWLOntologyID(name);
				}
				else {
					id = new OWLOntologyID(name, version);
				}
				change = new SetOntologyID(ontology, id);
			}
			else if (annotation.getProperty().equals(ChangeOntology.IMPORTS)) {
				
			}
		}
	}
	
	private int getRevision(Set<OWLAnnotation> annotations) {
		for (OWLAnnotation annotation : annotations) {
			if (annotation.getProperty().equals(ChangeOntology.REVISION)) {
				return ((OWLLiteral) annotation.getValue()).parseInteger();
			}
		}
		throw new IllegalStateException("Revision information expected but not found.");
	}
	
	private boolean isAdded(Set<OWLAnnotation> annotations) {
		for (OWLAnnotation annotation : annotations) {
			if (annotation.getProperty().equals(ChangeOntology.IS_AXIOM_ADDED)) {
				return ((OWLLiteral) annotation.getValue()).parseBoolean();
			}
		}
		throw new IllegalStateException("Added/Removed information expected but not found.");
	}

	private IRI getVersionIRI(Set<OWLAnnotation> annotations) {
		for (OWLAnnotation annotation : annotations) {
			if (annotation.getProperty().equals(ChangeOntology.SET_ONTOLOGY_VERSION)) {
				return (IRI) annotation.getValue();
			}
		}
		return null;
	}
	
	private Set<OWLAnnotation> removeChangeOntologyAnnotations(Set<OWLAnnotation> annotations) {
		Set<OWLAnnotation> cleanedAnnotations = new TreeSet<OWLAnnotation>(annotations);
		for (OWLAnnotation annotation : annotations) {
			if (annotation.getProperty().getIRI().toString().startsWith(ChangeOntology.NS)) {
				cleanedAnnotations.remove(annotation);
			}
		}
		return cleanedAnnotations;
	}

}