package com.dynatrace.loadrunner.converter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.dynatrace.loadrunner.Constants;
import com.dynatrace.loadrunner.config.Mode;
import com.dynatrace.loadrunner.converter.util.BodyFilePatcherUtil;
import com.google.common.collect.Lists;

abstract class AbstractBodyFilePatcher extends AbstractFilePatcher {

	private final String scriptName;

	static final String HEADER = Constants.DT_HEADER;
	private final String regex;
	private final String transactionStart;
	private final String transactionEnd;
	private final Set<String> keywords;
	private final Set<String> clickAndScript;
	private final char param;

	private final List<String> transactionNames = Lists.newArrayList();
	private final Map<String, Integer> transactionStartDepths = new HashMap<>();
	private String currentTransactionName = "";
	private int braceDepth = 0;

	AbstractBodyFilePatcher(Mode mode, String scriptName, boolean verbose, String regex, String transactionStart,
			String transactionEnd, Set<String> keywords, Set<String> clickAndScript, char param) {
		super(mode, verbose);
		this.scriptName = scriptName;
		this.regex = regex;
		this.transactionStart = transactionStart;
		this.transactionEnd = transactionEnd;
		this.keywords = keywords;
		this.clickAndScript = clickAndScript;
		this.param = param;
	}

	protected boolean patch(File sourceFile, File targetFile) throws IOException {
		try (
				BufferedReader reader = new BufferedReader(new FileReader(sourceFile));
				PrintWriter writer = new PrintWriter(targetFile)
		) {
			if (verbose) {
				System.out.printf("Patching file: %s%n", sourceFile.getAbsolutePath());
			}
			transactionNames.clear();
			transactionStartDepths.clear();
			braceDepth = 0;
			FileScanner scanner = new FileScanner(reader);	
			scanner.initialize();
			parseFile(scanner, writer);
			if(verbose && !transactionNames.isEmpty()) {
				System.out.printf("Some transactions possibly left open: %s%n", transactionNames.toString());
			}
		}
		return true;
	}

	private void parseFile(FileScanner scanner, PrintWriter writer) {
		if (verbose) {
			System.out.println("parsing...");
		}
		switch (mode) {
		case INSERT:
			while (scanner.goToNextInstruction()) {
				handleInsert(scanner, writer);
			}
			break;
		case DELETE:
			while (scanner.goToNextInstruction()) {
				handleDelete(scanner, writer);
			}
			break;
		default:
			throw new UnsupportedOperationException("Unknown patch mode: " + mode);
		}
		if (verbose) {
			System.out.println("... parsing done");
		}
	}

	private void handleInsert(FileScanner scanner, PrintWriter writer) {
    String instructionToWrite = BodyFilePatcherUtil.removeEOF(scanner.getUnmodifiedInstruction().toString());
    String instructionsWithoutComments = BodyFilePatcherUtil.removeEOF(scanner.getUnmodifiedInstructionWithoutComments().toString());
    String modInstr = scanner.getModifiedInstruction().toString();

    // Count opening braces absorbed into this instruction (they are not terminators
    // so they get consumed mid-instruction). Closing braces always terminate an
    // instruction, so the net depth change per instruction is:
    //   opens - (1 if terminated by '}', else 0)
    int openBraces = countChar(modInstr, Constants.CURLY_LEFT_BRACE);
    boolean closedByBrace = modInstr.length() > 0
            && modInstr.charAt(modInstr.length() - 1) == Constants.CURLY_RIGHT_BRACE;

    if (scanner.modifiedInstructionContains(transactionStart)) {
        String transactionName = BodyFilePatcherUtil.getFirstStringParameter(instructionsWithoutComments.substring(instructionsWithoutComments.indexOf(transactionStart)), param).trim();
        if (StringUtils.isNotBlank(transactionName)) {
            currentTransactionName = transactionName;
            transactionNames.add(transactionName);
            transactionStartDepths.put(transactionName, braceDepth);
        }
    } else if (scanner.modifiedInstructionContains(transactionEnd)) {
        String transactionName = BodyFilePatcherUtil.getFirstStringParameter(instructionsWithoutComments.substring(instructionsWithoutComments.indexOf(transactionEnd)), param).trim();
        if (StringUtils.isNotBlank(transactionName)) {
            int startDepth = transactionStartDepths.getOrDefault(transactionName, 0);
            // Only close the transaction when we are at or above the depth it was opened.
            // A deeper nesting level means this lr_end_transaction is inside a conditional
            // early-exit block (e.g. an error-handling if-branch) and the transaction
            // remains active on the normal code path.
            if (braceDepth <= startDepth) {
                if (verbose && !isCurrentTransaction(transactionName)) {
                    if (currentTransactionName.isEmpty()) {
                        System.out.printf("Invalid '%s', trying to end transaction '%s' which wasn't started yet, or is already closed%n",
                                transactionEnd, transactionName);
                    } else {
                        System.out.printf("Invalid '%s', trying to end transaction '%s' while current transaction is '%s'%n",
                                transactionEnd, transactionName, currentTransactionName);
                    }
                }
                transactionNames.remove(transactionName);
                transactionStartDepths.remove(transactionName);
                currentTransactionName = transactionNames.isEmpty() ? "" : transactionNames.get(transactionNames.size() - 1);
            }
        }
    } else {
        String keyword = processKeywords(modInstr);
        if (StringUtils.isNotBlank(keyword)) {
            String processedPage = BodyFilePatcherUtil
                    .getFirstStringParameter(modInstr, param);
            instructionToWrite = modifyInstruction(instructionToWrite, scanner.getWhiteSpace().toString(), keyword,
                    processedPage);
        }
    }

    // Update brace depth after processing this instruction
    braceDepth += openBraces;
    if (closedByBrace) {
        braceDepth--;
    }

    writer.write(instructionToWrite);
}

private static int countChar(String s, char target) {
    int count = 0;
    for (int i = 0; i < s.length(); i++) {
        if (s.charAt(i) == target) count++;
    }
    return count;
}
	private boolean isCurrentTransaction(String transactionName) {
		return currentTransactionName.equalsIgnoreCase(transactionName);
	}

	private void handleDelete(FileScanner scanner, PrintWriter writer) {
		String instructionToWrite = BodyFilePatcherUtil.removeEOF(scanner.getUnmodifiedInstruction().toString());
		if (scanner.modifiedInstructionContains(HEADER)) {
			writer.write(instructionToWrite.replaceAll(regex, ""));
			scanner.skipWhiteSpaces();
		} else {
			writer.write(instructionToWrite);
		}
	}

	private String processKeywords(String modifiedInstruction) {
		for (String keyword : keywords) {
			if (modifiedInstruction.contains(keyword)) {
				return keyword;
			}
		}
		return null;
	}

	private String modifyInstruction(String unmodifiedInstruction, String whiteSpaces, String keyword,
			String processedPage) {
		int insertPosition = BodyFilePatcherUtil.getInsertPosition(unmodifiedInstruction, keyword);
		return unmodifiedInstruction.substring(0, insertPosition)
				+ HEADER
				+ '(' + buildParameters(keyword, processedPage) + ");"
				+ Constants.CRLF
				+ whiteSpaces
				+ unmodifiedInstruction.substring(insertPosition);
	}

	private String buildParameters(String keyword, String processedPage) {
		StringBuilder parameterBuilder = new StringBuilder();
		parameterBuilder.append("\"");
		String tsn = BodyFilePatcherUtil.concatTransactionNames(transactionNames);
		if (StringUtils.isNotBlank(tsn)) {
			parameterBuilder.append("TSN=").append(tsn).append(';');
		}
		if (!clickAndScript.contains(keyword)) {
			parameterBuilder.append("PC=").append(processedPage).append(';');
			parameterBuilder.append("SI=LoadRunner;");
			if (StringUtils.isNotBlank(scriptName)) {
				parameterBuilder.append("LSN=").append(scriptName).append(';');
			}
		}
		parameterBuilder.append("\"");
		return parameterBuilder.toString();
	}

}
