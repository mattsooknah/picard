package picard.vcf;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.writer.Options;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFRecordCodec;
import htsjdk.variant.vcf.VCFUtils;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.CommandLineProgramProperties;
import picard.cmdline.Option;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.VcfOrBcf;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Sorts one or more VCF files according to the order of the contigs in the header/sequence dictionary and then
 * by coordinate.  Can accept an external dictionary. If no external dictionary is supplied, multiple inputs' headers must have
 * the same sequence dictionaries
 *
 */
@CommandLineProgramProperties(
        usage = "Sorts one or more VCF files according to the order of the contigs in the header/sequence dictionary and then by coordinate. " +
        "Can accept an external sequence dictionary. If no external dictionary is supplied, multiple inputs' headers must have " +
        "the same sequence dictionaries. Multiple inputs must have the same sample names (in order)\n",
        usageShort = "Sorts one or more VCF files",
        programGroup = VcfOrBcf.class
)
public class SortVcf extends CommandLineProgram {

    @Option(shortName= StandardOptionDefinitions.INPUT_SHORT_NAME, doc="Input VCF(s) to be sorted. Multiple inputs must have the same sample names (in order)")
    public List<File> INPUT;

    @Option(shortName= StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc="Output VCF to be written.")
    public File OUTPUT;

    @Option(shortName=StandardOptionDefinitions.SEQUENCE_DICTIONARY_SHORT_NAME, optional=true)
    public File SEQUENCE_DICTIONARY;

    private final Log log = Log.getInstance(SortVcf.class);

    private final List<VCFFileReader> inputReaders = new ArrayList<VCFFileReader>();
    private final List<VCFHeader> inputHeaders = new ArrayList<VCFHeader>();

    public static void main(final String[] args) {
        new SortVcf().instanceMainWithExit(args);
    }

    // Overrides the option default, including in the help message. Option remains settable on commandline.
    public SortVcf() {
        this.CREATE_INDEX = true;
    }

    @Override
    protected int doWork() {
        final List<String> sampleList = new ArrayList<String>();

        for (final File input : INPUT) IOUtil.assertFileIsReadable(input);

        if (SEQUENCE_DICTIONARY != null) IOUtil.assertFileIsReadable(SEQUENCE_DICTIONARY);

        SAMSequenceDictionary samSequenceDictionary = null;
        if (SEQUENCE_DICTIONARY != null) {
            samSequenceDictionary = SamReaderFactory.makeDefault().open(SEQUENCE_DICTIONARY).getFileHeader().getSequenceDictionary();
            CloserUtil.close(SEQUENCE_DICTIONARY);
        }

        // Gather up a file reader and file header for each input file. Check for sequence dictionary compatibility along the way.
        collectFileReadersAndHeaders(sampleList, samSequenceDictionary);

        // Create the merged output header from the input headers
        final VCFHeader outputHeader = new VCFHeader(VCFUtils.smartMergeHeaders(inputHeaders, false), sampleList);

        // Load entries into the sorting collection
        final SortingCollection<VariantContext> sortedOutput = sortInputs(inputReaders, outputHeader);

        // Output to the final file
        writeSortedOutput(outputHeader, sortedOutput);

        return 0;
    }

    private void collectFileReadersAndHeaders(final List<String> sampleList, SAMSequenceDictionary samSequenceDictionary) {
        for (final File input : INPUT) {
            final VCFFileReader in = new VCFFileReader(input, false);
            final VCFHeader header = in.getFileHeader();
            final SAMSequenceDictionary dict = in.getFileHeader().getSequenceDictionary();
            if (dict == null || dict.isEmpty()) {
                if (null == samSequenceDictionary) {
                    throw new IllegalArgumentException("Sequence dictionary was missing or empty for the VCF: " + input.getAbsolutePath() + " Please add a sequence dictionary to this VCF or specify SEQUENCE_DICTIONARY.");
                }
                header.setSequenceDictionary(samSequenceDictionary);
            } else {
                if (null == samSequenceDictionary) {
                    samSequenceDictionary = dict;
                } else {
                    try {
                        samSequenceDictionary.assertSameDictionary(dict);
                    } catch (final AssertionError e) {
                        throw new IllegalArgumentException(e);
                    }
                }
            }
            if (sampleList.isEmpty()) {
                sampleList.addAll(header.getSampleNamesInOrder());
            } else {
                if ( !sampleList.equals(header.getSampleNamesInOrder())) {
                    throw new IllegalArgumentException("Input file " + input.getAbsolutePath() + " has sample names that don't match the other files.");
                }
            }
            inputReaders.add(in);
            inputHeaders.add(header);
        }
    }

    /**
     * Merge the inputs and sort them by adding each input's content to a single SortingCollection.
     *
     * NB: It would be better to have a merging iterator as in MergeSamFiles, as this would perform better for pre-sorted inputs.
     * Here, we are assuming inputs are unsorted, and so adding their VariantContexts iteratively is fine for now.
     * MergeVcfs exists for simple merging of presorted inputs.
     *
     * @param readers - a list of VCFFileReaders, one for each input VCF
     * @param outputHeader - The merged header whose information we intend to use in the final output file
     */
    private SortingCollection<VariantContext> sortInputs(final List<VCFFileReader> readers, final VCFHeader outputHeader) {
        final ProgressLogger readProgress = new ProgressLogger(log, 25000, "read", "records");

        // NB: The default MAX_RECORDS_IN_RAM may not be appropriate here. VariantContexts are smaller than SamRecords
        // We would have to play around empirically to find an appropriate value. We are not performing this optimization at this time.
        final SortingCollection<VariantContext> sorter =
                SortingCollection.newInstance(
                        VariantContext.class,
                        new VCFRecordCodec(outputHeader),
                        outputHeader.getVCFRecordComparator(),
                        MAX_RECORDS_IN_RAM,
                        TMP_DIR);
        int readerCount = 1;
        for (final VCFFileReader reader : readers) {
            log.info("Reading entries from input file " + readerCount);
            for (final VariantContext variantContext : reader) {
                sorter.add(variantContext);
                readProgress.record(variantContext.getChr(), variantContext.getStart());
            }
            reader.close();
            readerCount++;
        }
        return sorter;
    }

    private void writeSortedOutput(final VCFHeader outputHeader, final SortingCollection<VariantContext> sortedOutput) {
        final ProgressLogger writeProgress = new ProgressLogger(log, 25000, "wrote", "records");
        final EnumSet<Options> options = CREATE_INDEX ? EnumSet.of(Options.INDEX_ON_THE_FLY) : EnumSet.noneOf(Options.class);
        final VariantContextWriter out = new VariantContextWriterBuilder().
                setReferenceDictionary(outputHeader.getSequenceDictionary()).
                setOptions(options).
                setOutputFile(OUTPUT).build();
        out.writeHeader(outputHeader);
        for (final VariantContext variantContext : sortedOutput) {
            out.add(variantContext);
            writeProgress.record(variantContext.getChr(), variantContext.getStart());
        }
        out.close();
    }
}
