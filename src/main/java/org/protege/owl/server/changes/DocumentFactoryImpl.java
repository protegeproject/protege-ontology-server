package org.protege.owl.server.changes;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.coode.owlapi.owlxmlparser.OWLXMLParserHandler;
import org.protege.owl.server.api.ChangeDocument;
import org.protege.owl.server.api.ChangeMetaData;
import org.protege.owl.server.api.DocumentFactory;
import org.protege.owl.server.api.OntologyDocumentRevision;
import org.protege.owl.server.api.RemoteOntologyDocument;
import org.protege.owl.server.api.VersionedOWLOntology;
import org.protege.owl.server.changes.format.OWLInputStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.xml.sax.SAXException;

public class DocumentFactoryImpl implements DocumentFactory, Serializable {
    private static final long serialVersionUID = -4952738108103836430L;
    public static Logger logger = Logger.getLogger(DocumentFactoryImpl.class.getCanonicalName());
	
    @Override
    public ChangeDocument createEmptyChangeDocument(OntologyDocumentRevision revision) {
        return new ChangeDocumentImpl(this, revision, new ArrayList<OWLOntologyChange>(), null);
    }
    
	@Override
	public ChangeDocument createChangeDocument(List<OWLOntologyChange> changes,
											   ChangeMetaData metaData, 
											   OntologyDocumentRevision start) {
		return new ChangeDocumentImpl(this, start, changes, metaData);
	}
	
	@Override
	public VersionedOWLOntology createVersionedOntology(OWLOntology ontology,
													    RemoteOntologyDocument serverDocument,
													    OntologyDocumentRevision revision) {
		ChangeDocument localChanges = createEmptyChangeDocument(OntologyDocumentRevision.START_REVISION);
		List<ChangeDocument> previousCommits = new ArrayList<ChangeDocument>();
		return new VersionedOWLOntologyImpl(ontology, serverDocument, revision, localChanges, previousCommits);
	}
	
	@Override
	public boolean hasServerMetadata(OWLOntology ontology) {
		File ontologyFile = VersionedOWLOntologyImpl.getBackingStore(ontology);
		if (ontologyFile == null) {
			return false;
		}
		return VersionedOWLOntologyImpl.getHistoryFile(ontologyFile).exists();
	}

	@Override
	public VersionedOWLOntology createVersionedOntology(OWLOntology ontology) throws IOException {
	    try {
	        File ontologyFile = VersionedOWLOntologyImpl.getBackingStore(ontology);
	        File historyFile = VersionedOWLOntologyImpl.getHistoryFile(ontologyFile);
	        ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(historyFile)));
	        OntologyDocumentRevision revision = (OntologyDocumentRevision) ois.readObject();
	        RemoteOntologyDocument serverDocument = (RemoteOntologyDocument) ois.readObject();
	        ChangeDocument localChanges = (ChangeDocument) ois.readObject();
	        @SuppressWarnings("unchecked")
            List<ChangeDocument> committedChanges = (List<ChangeDocument>) ois.readObject();
	        return new VersionedOWLOntologyImpl(ontology, serverDocument, revision, localChanges, committedChanges);
	    }
	    catch (ClassNotFoundException cnfe) {
	        throw new IOException("Class Loader issues when hydrating ontology history document", cnfe);
	    }
	}
	
	/*
     * This algorithm involves a copy of the input stream which would be nice to avoid.
	 */
	@SuppressWarnings("deprecation")
	@Override
	public ChangeDocument readChangeDocument(InputStream in,
											 OntologyDocumentRevision start, OntologyDocumentRevision end) throws IOException {
        ObjectInputStream ois;
	    try {
			if (in instanceof ObjectInputStream) {
				ois = (ObjectInputStream) in;
			}
			else {
				ois = new ObjectInputStream(in);
			}
			OntologyDocumentRevision startRevision = (OntologyDocumentRevision) ois.readObject();
			if (start == null) {
			    start = startRevision;
			}
			@SuppressWarnings("unchecked")
			SortedMap<OntologyDocumentRevision, ChangeMetaData> metaData = (SortedMap<OntologyDocumentRevision, ChangeMetaData>) ois.readObject();
			List<List<OWLOntologyChange>> changes = new ArrayList<List<OWLOntologyChange>>();
			OWLInputStream owlStream = new OWLInputStream(ois);
			int count = ois.readInt();
			OntologyDocumentRevision revision = startRevision;
			for (int i = 0; i < count; i++,revision = revision.next()) {
			    @SuppressWarnings("unchecked")
                List<OWLOntologyChange> changeList = (List<OWLOntologyChange>) owlStream.read();
			    if (revision.compareTo(start) >= 0 && (end == null || revision.compareTo(end) < 0)) {
			        changes.add(changeList);
			    }
			}
			if (end == null) {
			    end = start.add(changes.size());
			}
			metaData = metaData.tailMap(start).headMap(end);
            return new ChangeDocumentImpl(start, this, changes, metaData); 
		}
		catch (IOException ioe) {
			logger.log(Level.WARNING, "Exception caught deserializing change document", ioe);
			throw ioe;
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "Exception caught deserializing change document", e);
			throw new IOException(e);
		}
		catch (Error e) {
			logger.log(Level.WARNING, "Exception caught deserializing change document", e);
			throw e;
		}
	}



}