/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-03,  Wolfgang M. Meier (wolfgang@exist-db.org)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU Library General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 *  $Id:
 */
package org.exist.storage;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.dbxml.core.DBException;
import org.dbxml.core.data.Value;
import org.dbxml.core.filer.BTreeException;
import org.dbxml.core.indexer.IndexQuery;
import org.exist.collections.Collection;
import org.exist.dom.DocumentImpl;
import org.exist.dom.NodeImpl;
import org.exist.dom.NodeProxy;
import org.exist.dom.QName;
import org.exist.dom.XMLUtil;
import org.exist.storage.io.VariableByteArrayInput;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.storage.store.BFile;
import org.exist.storage.store.StorageAddress;
import org.exist.util.Configuration;
import org.exist.util.FastQSort;
import org.exist.util.Lock;
import org.exist.util.LockException;
import org.exist.util.ProgressIndicator;
import org.exist.util.ReadOnlyException;
import org.exist.xquery.TerminatedException;

public class NativeElementIndex extends ElementIndex {

	private static Logger LOG = Logger.getLogger(NativeElementIndex.class
			.getName());

	protected BFile dbElement;
	private VariableByteOutputStream os = new VariableByteOutputStream();

	public NativeElementIndex(DBBroker broker, Configuration config,
			BFile dbElement) {
		super(broker, config);
		this.dbElement = dbElement;
	}

	public void addRow(QName qname, NodeProxy proxy) {
		ArrayList buf;
		if (elementIds.containsKey(qname))
			buf = (ArrayList) elementIds.get(qname);
		else {
			buf = new ArrayList(50);
			elementIds.put(qname, buf);
		}
		buf.add(proxy);
	}

	public void dropIndex(Collection collection) {
	    LOG.debug("removing elements ...");
	    Value ref = new ElementValue(collection.getId());
		IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
		Lock lock = dbElement.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
			dbElement.removeAll(query);
		} catch (LockException e) {
			LOG.error("could not acquire lock on elements index", e);
		} catch (BTreeException e) {
            LOG.warn(e.getMessage(), e);
        } catch (IOException e) {
            LOG.warn(e.getMessage(), e);
        } finally {
			lock.release();
		}
	}
	
	public void dropIndex(DocumentImpl doc) throws ReadOnlyException {
//	  drop element-index
		short collectionId = doc.getCollection().getId();
		Value ref = new ElementValue(collectionId);
		IndexQuery query = new IndexQuery(IndexQuery.TRUNC_RIGHT, ref);
		Lock lock = dbElement.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
			ArrayList elements = dbElement.findKeys(query);
            if (LOG.isDebugEnabled()) {
                LOG.debug("removeDocument() - "
                    + "found "
                    + elements.size()
                    + " elements.");
            }

			Value key;
			Value value;
			byte[] data;
			// byte[] ndata;
			VariableByteArrayInput is;
			VariableByteOutputStream os;
			int len;
			int docId;
			long delta;
			long address;
			boolean changed;
			for (int i = 0; i < elements.size(); i++) {
				key = (Value) elements.get(i);
				value = dbElement.get(key);
				data = value.getData();
				is = new VariableByteArrayInput(data);
				os = new VariableByteOutputStream();
				changed = false;
				try {
					while (is.available() > 0) {
						docId = is.readInt();
						len = is.readInt();
						if (docId != doc.getDocId()) {
							// copy data to new buffer
							os.writeInt(docId);
							os.writeInt(len);
							for (int j = 0; j < len; j++) {
								delta = is.readLong();
								address = StorageAddress.read(is);
								os.writeLong(delta);
								StorageAddress.write(address, os);
							}
						} else {
							changed = true;
							// skip
							is.skip(len * 4);
						}
					}
				} catch (EOFException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("removeDocument(String) - eof", e);
                    }
				} catch (IOException e) {
                    LOG.warn("removeDocument(String) " + e.getMessage(), e);
                }
				if (changed) {
					if (dbElement.put(key, os.data()) < 0)
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("removeDocument() - "
                                + "could not save element");
                        }
				}
			}
		} catch (LockException e) {
            LOG.warn("removeDocument(String) - "
                + "could not acquire lock on elements", e);
		} catch (TerminatedException e) {
            LOG.warn("method terminated", e);
        } catch (BTreeException e) {
            LOG.warn(e.getMessage(), e);
        } catch (IOException e) {
            LOG.warn(e.getMessage(), e);
        } finally {
			lock.release();
		}
	}
	
	public void reindex(DocumentImpl oldDoc, NodeImpl node) {
		if (elementIds.size() == 0)
			return;
		Lock lock = dbElement.getLock();
		Map.Entry entry;
		QName qname;
		List oldList = new ArrayList(), idList;
		NodeProxy p;
		VariableByteInput is = null;
		int len, docId;
		byte[] data;
		Value ref;
		Value val;
		short sym, nsSym;
		short collectionId = oldDoc.getCollection().getId();
		long delta, last, gid, address;
		try {
			// iterate through elements
			for (Iterator i = elementIds.entrySet().iterator(); i.hasNext(); ) {
				entry = (Map.Entry) i.next();
				idList = (ArrayList) entry.getValue();
				qname = (QName) entry.getKey();
				if (qname.getNameType() != ElementValue.ATTRIBUTE_ID) {
					sym = broker.getSymbols().getSymbol(qname.getLocalName());
					nsSym = broker.getSymbols().getNSSymbol(
							qname.getNamespaceURI());
					ref = new ElementValue(qname.getNameType(), collectionId,
							sym, nsSym);
				} else
					ref = new ElementValue(qname.getNameType(), collectionId,
							qname.getLocalName());
				// try to retrieve old index entry for the element
				try {
					lock.acquire(Lock.READ_LOCK);
					//val = dbElement.get(ref);
					is = dbElement.getAsStream(ref);
				} catch (LockException e) {
					LOG.error("could not acquire lock for index on " + qname);
					return;
				} catch (IOException e) {
					LOG.error("io error while reindexing " + qname, e);
					is = null;
				} finally {
					lock.release();
				}
				os.clear();
				oldList.clear();
				if (is != null) {
					// add old entries to the new list 
					//data = val.getData();
					try {
						while (is.available() > 0) {
							docId = is.readInt();
							len = is.readInt();
							if (docId != oldDoc.getDocId()) {
								// section belongs to another document:
								// copy data to new buffer
								os.writeInt(docId);
								os.writeInt(len);
								is.copyTo(os, len * 4);
							} else {
								// copy nodes to new list
								last = 0;
								for (int j = 0; j < len; j++) {
									delta = is.readLong();
									gid = last + delta;
									last = gid;
									//address = is.readFixedLong();
									address = StorageAddress.read(is);
									if (node == null
											&& oldDoc.getTreeLevel(gid) < oldDoc
													.reindexRequired()) {
										idList.add(new NodeProxy(oldDoc, gid,
												address));
									} else if (node != null
											&& (!XMLUtil.isDescendant(
													oldDoc, node.getGID(), gid))) {
										oldList.add(new NodeProxy(oldDoc, gid,
												address));
									}
								}
							}
						}
					} catch (EOFException e) {
					} catch (IOException e) {
						LOG.error("io-error while updating index for element "
								+ qname);
					}
				}
				if (node != null)
					idList.addAll(oldList);
				// write out the updated list
				FastQSort.sort(idList, 0, idList.size() - 1);
				len = idList.size();
				os.writeInt(doc.getDocId());
				os.writeInt(len);
				last = 0;
				for (int j = 0; j < len; j++) {
					p = (NodeProxy) idList.get(j);
					delta = p.gid - last;
					last = p.gid;
					os.writeLong(delta);
					StorageAddress.write(p.getInternalAddress(), os);
				}
				//data = os.toByteArray();
				try {
					lock.acquire(Lock.WRITE_LOCK);
					if (is == null)
						dbElement.put(ref, os.data());
					else {
						address = ((BFile.PageInputStream) is).getAddress();
						dbElement.update(address, ref, os.data());
						//dbElement.update(val.getAddress(), ref, data);
					}
				} catch (LockException e) {
					LOG.error("could not acquire lock on elements", e);
				} finally {
					lock.release();
				}
			}
		} catch (ReadOnlyException e) {
			LOG.warn("database is read only");
		}
		elementIds.clear();
	}

	public void remove() {
		if (elementIds.size() == 0)
			return;
		Lock lock = dbElement.getLock();
		Map.Entry entry;
		QName qname;
		List newList = new ArrayList(), idList;
		NodeProxy p;
		VariableByteArrayInput is;
		int len, docId;
		byte[] data;
		Value ref;
		Value val;
		short sym, nsSym;
		short collectionId = doc.getCollection().getId();
		long delta, last, gid, address;
		try {
			// iterate through elements
			for (Iterator i = elementIds.entrySet().iterator(); i.hasNext(); ) {
				entry = (Map.Entry) i.next();
				idList = (ArrayList) entry.getValue();
				qname = (QName) entry.getKey();
				if (qname.getNameType() != ElementValue.ATTRIBUTE_ID) {
					sym = broker.getSymbols().getSymbol(qname.getLocalName());
					nsSym = broker.getSymbols().getNSSymbol(
							qname.getNamespaceURI());
					ref = new ElementValue(qname.getNameType(), collectionId,
							sym, nsSym);
				} else {
					ref = new ElementValue(qname.getNameType(), collectionId,
							qname.getLocalName());
				}
				// try to retrieve old index entry for the element
				try {
					lock.acquire(Lock.READ_LOCK);
					val = dbElement.get(ref);
				} catch (LockException e) {
					LOG.error("could not acquire lock for index on " + qname);
					return;
				} finally {
					lock.release();
				}
				os.clear();
				newList.clear();
				if (val != null) {
					// add old entries to the new list 
					data = val.getData();
					is = new VariableByteArrayInput(data);
					try {
						while (is.available() > 0) {
							docId = is.readInt();
							len = is.readInt();
							if (docId != doc.getDocId()) {
								// section belongs to another document:
								// copy data to new buffer
								os.writeInt(docId);
								os.writeInt(len);
								is.copyTo(os, len * 4);
							} else {
								// copy nodes to new list
								last = 0;
								for (int j = 0; j < len; j++) {
									delta = is.readLong();
									gid = last + delta;
									last = gid;
									address = StorageAddress.read(is);
									//address = is.readFixedLong();
									if (!containsNode(idList, gid)) {
										newList.add(new NodeProxy(doc, gid,
												address));
									}
								}
							}
						}
					} catch (EOFException e) {
						LOG
								.error("end-of-file while updating index for element "
										+ qname);
					} catch (IOException e) {
						LOG.error("io-error while updating index for element "
								+ qname);
					}
				}
				// write out the updated list
				FastQSort.sort(newList, 0, newList.size() - 1);
				len = newList.size();
				os.writeInt(doc.getDocId());
				os.writeInt(len);
				last = 0;
				for (int j = 0; j < len; j++) {
					p = (NodeProxy) newList.get(j);
					delta = p.gid - last;
					last = p.gid;
					os.writeLong(delta);
					StorageAddress.write(p.getInternalAddress(), os);
				}
				try {
					lock.acquire(Lock.WRITE_LOCK);
					if (val == null) {
						dbElement.put(ref, os.data());
					} else {
						dbElement.update(val.getAddress(), ref, os.data());
					}
				} catch (LockException e) {
					LOG.error("could not acquire lock on elements", e);
				} finally {
					lock.release();
				}
			}
		} catch (ReadOnlyException e) {
			LOG.warn("database is read only");
		}
		elementIds.clear();
	}

	private final static boolean containsNode(List list, long gid) {
		for (int i = 0; i < list.size(); i++)
			if (((NodeProxy) list.get(i)).gid == gid)
				return true;
		return false;
	}

	public void flush() {
		if (elementIds.size() == 0)
			return;
		final ProgressIndicator progress = new ProgressIndicator(elementIds
				.size(), 5);

		NodeProxy proxy;
		QName qname;
		ArrayList idList;
		int count = 1, len;
		byte[] data;
		String name;
		ElementValue ref;
		Map.Entry entry;
		// get collection id for this collection
		final String docName = doc.getFileName();
		long prevId;
		long cid;
		long addr;
		short collectionId = doc.getCollection().getId();
		Lock lock = dbElement.getLock();
		try {
			for (Iterator i = elementIds.entrySet().iterator(); i.hasNext(); ) {
				entry = (Map.Entry) i.next();
				qname = (QName) entry.getKey();
				idList = (ArrayList) entry.getValue();
				os.clear();
				FastQSort.sort(idList, 0, idList.size() - 1);
				len = idList.size();
				os.writeInt(doc.getDocId());
				os.writeInt(len);
				prevId = 0;
				for (int j = 0; j < len; j++) {
					proxy = (NodeProxy) idList.get(j);
					cid = proxy.gid - prevId;
					prevId = proxy.gid;
					os.writeLong(cid);
					StorageAddress.write(proxy.getInternalAddress(), os);
				}
				if (qname.getNameType() != ElementValue.ATTRIBUTE_ID) {
					short sym = broker.getSymbols().getSymbol(
							qname.getLocalName());
					short nsSym = broker.getSymbols().getNSSymbol(
							qname.getNamespaceURI());
					ref = new ElementValue(qname.getNameType(), collectionId,
							sym, nsSym);
				} else {
					ref = new ElementValue(qname.getNameType(), collectionId,
							qname.getLocalName());
				}
				try {
					lock.acquire(Lock.WRITE_LOCK);
					if (dbElement.append(ref, os.data()) < 0) {
						LOG.warn("could not save index for element " + qname);
						continue;
					}
				} catch (LockException e) {
					LOG.error("could not acquire lock on elements", e);
				} catch (IOException e) {
					LOG.error("io error while writing element " + qname, e);
				} finally {
					lock.release();
				}
				progress.setValue(count);
				if (progress.changed()) {
					setChanged();
					notifyObservers(progress);
				}
				count++;
			}
		} catch (ReadOnlyException e) {
			LOG.warn("database is read-only");
			return;
		}
		progress.finish();
		setChanged();
		notifyObservers(progress);
		elementIds.clear();
	}

	public void sync() {
		Lock lock = dbElement.getLock();
		try {
			lock.acquire(Lock.WRITE_LOCK);
			try {
				dbElement.flush();
			} catch (DBException dbe) {
				LOG.warn(dbe);
			}
		} catch (LockException e) {
			LOG.warn("could not acquire lock for elements", e);
		} finally {
			lock.release();
		}
	}
}
