/*******************************************************************************
 * Copyright (c) 2013 Olaf Lessenich.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     Olaf Lessenich - initial API and implementation
 ******************************************************************************/
/**
 * 
 */
package de.fosd.jdime.strategy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;

import org.apache.log4j.Logger;

import de.fosd.jdime.common.ASTNodeArtifact;
import de.fosd.jdime.common.FileArtifact;
import de.fosd.jdime.common.MergeContext;
import de.fosd.jdime.common.MergeTriple;
import de.fosd.jdime.common.NotYetImplementedException;
import de.fosd.jdime.common.operations.MergeOperation;
import de.fosd.jdime.stats.MergeTripleStats;
import de.fosd.jdime.stats.Stats;
import de.fosd.jdime.stats.StatsElement;

/**
 * Performs a structured merge.
 * 
 * @author Olaf Lessenich
 * 
 */
public class StructuredStrategy extends MergeStrategy<FileArtifact> {

	/**
	 * Logger.
	 */
	private static final Logger LOG = Logger
			.getLogger(StructuredStrategy.class);

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.fosd.jdime.strategy.MergeStrategy#merge(
	 * de.fosd.jdime.common.operations.MergeOperation,
	 * de.fosd.jdime.common.MergeContext)
	 */
	@Override
	public final void merge(final MergeOperation<FileArtifact> operation,
			final MergeContext context) throws IOException,
			InterruptedException {

		assert (operation != null);
		assert (context != null);

		MergeTriple<FileArtifact> triple = operation.getMergeTriple();

		assert (triple != null);
		assert (triple.isValid()) : "The merge triple is not valid!";
		assert (triple.getLeft() instanceof FileArtifact);
		assert (triple.getBase() instanceof FileArtifact);
		assert (triple.getRight() instanceof FileArtifact);

		assert (triple.getLeft().exists() && !triple.getLeft().isDirectory());
		assert ((triple.getBase().exists() && !triple.getBase().isDirectory()) 
				|| triple.getBase().isEmptyDummy());
		assert (triple.getRight().exists() && !triple.getRight().isDirectory());

		context.resetStreams();

		FileArtifact target = null;

		if (operation.getTarget() != null) {
			assert (operation.getTarget() instanceof FileArtifact);
			target = (FileArtifact) operation.getTarget();
			assert (!target.exists() || target.isEmpty()) 
				: "Would be overwritten: " + target;
		}

		// ASTNodeArtifacts are created from the input files.
		// Then, a ASTNodeStrategy can be applied.
		// The Result is pretty printed and can be written into the output file.

		ASTNodeArtifact left, base, right;
		ArrayList<Long> runtimes = new ArrayList<>();
		MergeContext mergeContext = null;
		int conflicts = 0;
		int loc = 0;
		int cloc = 0;

		if (LOG.isDebugEnabled()) {
			LOG.debug("Merging: " + triple.getLeft().getPath() + " "
					+ triple.getBase().getPath() + " "
					+ triple.getRight().getPath());
		}
		try {
			for (int i = 0; i < context.getBenchmarkRuns() + 1
					&& (i == 0 || context.isBenchmark()); i++) {
				if (i == 0 && (!context.isBenchmark() || context.hasStats())) {
					mergeContext = context;
				} else {
					mergeContext = (MergeContext) context.clone();
					mergeContext.setSaveStats(false);
					mergeContext.setOutputFile(null);
				}
				
				long cmdStart = System.currentTimeMillis();

				left = new ASTNodeArtifact(triple.getLeft());
				base = new ASTNodeArtifact(triple.getBase());
				right = new ASTNodeArtifact(triple.getRight());

				// Output tree
				// Program program = new Program();
				// program.state().reset();
				// ASTNodeArtifact targetNode = new ASTNodeArtifact(program);
				ASTNodeArtifact targetNode = ASTNodeArtifact
						.createProgram(left);
				targetNode.setRevision(left.getRevision());
				targetNode.forceRenumbering();

				if (LOG.isTraceEnabled()) {
					LOG.trace("target.dumpTree(:");
					System.out.println(targetNode.dumpTree());
				}

				MergeTriple<ASTNodeArtifact> nodeTriple 
					= new MergeTriple<ASTNodeArtifact>(triple.getMergeType(), 
							left, base, right);

				MergeOperation<ASTNodeArtifact> astMergeOp 
					= new MergeOperation<ASTNodeArtifact>(nodeTriple, 
							targetNode);

				if (LOG.isTraceEnabled()) {
					LOG.trace("ASTMOperation.apply(context)");
				}

				astMergeOp.apply(mergeContext);

				if (i == 0 && (!context.isBenchmark() || context.hasStats())) {
					if (LOG.isTraceEnabled()) {
						LOG.trace("Structured merge finished.");
						LOG.trace("target.dumpTree():");
						System.out.println(targetNode.dumpTree());

						LOG.trace("Pretty-printing left:");
						System.out.println(left.prettyPrint());
						LOG.trace("Pretty-printing right:");
						System.out.println(right.prettyPrint());
						LOG.trace("Pretty-printing merge:");
						if (mergeContext.isQuiet()) {
							System.out.println(targetNode.prettyPrint());
						}
					}
					
					// process input stream
					BufferedReader buf = new BufferedReader(new StringReader(
							targetNode.prettyPrint()));
					boolean conflict = false;
					boolean afterconflict = false;
					boolean inleft = false;
					boolean inright = false;

					int tmp = 0;
					String line = "";
					StringBuffer leftlines = null;
					StringBuffer rightlines = null;

					while ((line = buf.readLine()) != null) {
						if (line.matches("^$") || line.matches("^\\s*$")) {
							// skip empty lines
							if (!conflict && !afterconflict) {
								mergeContext.appendLine(line);
							}
							continue;
						}

						if (line.matches("^\\s*<<<<<<<.*")) {
							conflict = true;
							tmp = cloc;
							conflicts++;
							inleft = true;

							if (!afterconflict) {
								// new conflict or new chain of conflicts
								leftlines = new StringBuffer();
								rightlines = new StringBuffer();
							} else {
								// is directly after a previous conflict
								// lets merge them
								conflicts--;
							}
						} else if (line.matches("^\\s*=======.*")) {
							inleft = false;
							inright = true;
						} else if (line.matches("^\\s*>>>>>>>.*")) {
							conflict = false;
							afterconflict = true;
							if (tmp == cloc) {
								// only empty lines
								conflicts--;
							}
							inright = false;
						} else {
							loc++;
							if (conflict) {
								cloc++;
								if (inleft) {
									leftlines.append(line
											+ System.lineSeparator());
								} else if (inright) {
									rightlines.append(line
											+ System.lineSeparator());
								}
							} else {
								if (afterconflict) {
									// need to print the previous conflict(s)
									mergeContext.appendLine("<<<<<<< ");
									mergeContext.append(leftlines.toString());
									mergeContext.appendLine("======= ");
									mergeContext.append(rightlines.toString());
									mergeContext.appendLine(">>>>>>> ");
								}
								afterconflict = false;
								mergeContext.appendLine(line);
							}
						}
					}
				}

				long runtime = System.currentTimeMillis() - cmdStart;
				runtimes.add(runtime);

				if (LOG.isInfoEnabled() && context.isBenchmark() 
						&& context.hasStats()) {
					if (i == 0) {
						LOG.info("Initial run: " + runtime + " ms");
					} else {
						LOG.info("Run " + i + " of "
								+ context.getBenchmarkRuns() + ": "
								+ runtime + " ms");
					}
				}
			}
			if (context.isBenchmark() && runtimes.size() > 1) {
				// remove first run as it took way longer due to all the
				// counting
				runtimes.remove(0);
			}

			Long runtime = MergeContext.median(runtimes);
			LOG.debug("Structured merge time was " + runtime + " ms.");

			if (context.hasErrors()) {
				System.err.println(context.getStdErr());
			}

			// write output
			if (target != null) {
				assert (target.exists());
				target.write(context.getStdIn());
			}
			// add statistical data to context
			if (context.hasStats()) {
				assert (cloc <= loc);

				Stats stats = context.getStats();
				StatsElement linesElement = stats.getElement("lines");
				assert (linesElement != null);
				StatsElement newElement = new StatsElement();
				newElement.setMerged(loc);
				newElement.setConflicting(cloc);
				linesElement.addStatsElement(newElement);

				if (conflicts > 0) {
					assert (cloc > 0);
					stats.addConflicts(conflicts);
					StatsElement filesElement = stats.getElement("files");
					assert (filesElement != null);
					filesElement.incrementConflicting();
				} else {
					assert (cloc == 0);
				}

				stats.increaseRuntime(runtime);

				MergeTripleStats scenariostats = new MergeTripleStats(triple,
						conflicts, cloc, loc, runtime);
				stats.addScenarioStats(scenariostats);
			}
		} catch (Throwable t) {
			LOG.fatal(t + "  while merging " + triple.getLeft().getPath()
						+ " " + triple.getBase().getPath() + " "
						+ triple.getRight().getPath());
			if (!context.isKeepGoing()) {
				throw new Error(t);
			} else {
				if (context.hasStats()) {
					MergeTripleStats scenariostats = new MergeTripleStats(
							triple, t.toString());
					context.getStats().addScenarioStats(scenariostats);
				}
			}
		}
		System.gc();

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.fosd.jdime.strategy.MergeStrategy#toString()
	 */
	@Override
	public final String toString() {
		return "structured";
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see de.fosd.jdime.strategy.StatsInterface#createStats()
	 */
	@Override
	public final Stats createStats() {
		return new Stats(new String[] { "directories", "files", "lines",
				"nodes" });
	}

	@Override
	public final String getStatsKey(final FileArtifact artifact) {
		// FIXME: remove me when implementation is complete!
		throw new NotYetImplementedException(
				"StructuredStrategy: Implement me!");
	}

	@Override
	public final void dump(final FileArtifact artifact, final boolean graphical)
			throws IOException {
		new ASTNodeStrategy().dump(new ASTNodeArtifact(artifact), graphical);
	}

}
