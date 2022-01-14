package tml.centralapi.validatormain.services;

import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.mobilitydata.gtfsvalidator.input.CountryCode;
import org.mobilitydata.gtfsvalidator.input.CurrentDateTime;
import org.mobilitydata.gtfsvalidator.input.GtfsInput;
import org.mobilitydata.gtfsvalidator.input.GtfsZipFileInput;
import org.mobilitydata.gtfsvalidator.notice.IOError;
import org.mobilitydata.gtfsvalidator.notice.NoticeContainer;
import org.mobilitydata.gtfsvalidator.notice.URISyntaxError;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedContainer;
import org.mobilitydata.gtfsvalidator.table.GtfsFeedLoader;
import org.mobilitydata.gtfsvalidator.validator.DefaultValidatorProvider;
import org.mobilitydata.gtfsvalidator.validator.ValidationContext;
import org.mobilitydata.gtfsvalidator.validator.ValidatorLoader;
import org.mobilitydata.gtfsvalidator.validator.ValidatorLoaderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tml.centralapi.validatormain.model.IntendedOfferUpload;
import tml.centralapi.validatormain.repository.IntendedOfferUploadRepository;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class ValidatorAsyncService {
    @Autowired
    IntendedOfferUploadRepository mongoRepository;

    @Autowired
    IntendedOfferPgService pgService;

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private static final int numberOfThreads = 1;

    public IntendedOfferUpload getInput() {
        return input;
    }

    public void setInput(IntendedOfferUpload input) {
        this.input = input;
    }

    private IntendedOfferUpload input;

    @Async
    public void validateAsync() throws InterruptedException {
        String id = this.input.getId();
        IntendedOfferUpload upload = null;
        try {
            upload = mongoRepository.findById(id).orElseThrow(() -> new Exception("not found"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.input = upload;

        ValidatorLoader validatorLoader = null;
        try {
            validatorLoader = new ValidatorLoader();
        } catch (ValidatorLoaderException e) {
            logger.atSevere().withCause(e).log("Cannot load validator classes");
            System.exit(1);
        }
        GtfsFeedLoader feedLoader = new GtfsFeedLoader();

        System.out.println("Table loaders: " + feedLoader.listTableLoaders());
        System.out.println("Validators:");
        System.out.println(validatorLoader.listValidators());

        final long startNanos = System.nanoTime();
        // Input.
        feedLoader.setNumThreads(numberOfThreads);
        NoticeContainer noticeContainer = new NoticeContainer();
        GtfsFeedContainer feedContainer;
        GtfsInput gtfsInput = null;

        try {
            byte[] file = this.input.getFile().getData();
            gtfsInput = createGtfsInput(file);
        } catch (IOException e) {
            String err1 = "Cannot load GTFS feed";
            logger.atSevere().withCause(e).log(err1);
            noticeContainer.addSystemError(new IOError(e));
        } catch (URISyntaxException e) {
            String err2 = "Syntax error in URI";
            logger.atSevere().withCause(e).log(err2);
            noticeContainer.addSystemError(new URISyntaxError(e));
        }

        if (gtfsInput == null) {
            exportReport(noticeContainer);
            System.exit(1);
        }

        ValidationContext validationContext =
                ValidationContext.builder()
                        .setCountryCode(
                                CountryCode.forStringOrUnknown(CountryCode.ZZ))
                        .setCurrentDateTime(new CurrentDateTime(ZonedDateTime.now(ZoneId.systemDefault())))
                        .build();
        try {
            feedContainer =
                    loadAndValidate(
                            validatorLoader, feedLoader, noticeContainer, gtfsInput, validationContext);
            printSummary(startNanos, feedContainer);

            // POSTGRES
//            IntendedOfferPgService pgService = new IntendedOfferPgService();
            pgService.addToDatabase(feedContainer);
            // POSTGRES

        } catch (InterruptedException e) {
            String err3 = "Validation was interrupted";
            logger.atSevere().withCause(e).log(err3);
        } catch (Exception e) {
            e.printStackTrace();
        }

        exportReport(noticeContainer);
    }

    /** Generates and exports reports for both validation notices and system errors reports. */
    @Async
    public void exportReport(final NoticeContainer noticeContainer) {
        Gson gson = new Gson();
        String validations = gson.toJson(noticeContainer.exportValidationNotices());
        String errors = gson.toJson(noticeContainer.exportSystemErrors());
        this.input.setValidationReport(validations);
        this.input.setErrorsReport(errors);
        mongoRepository.save(this.input);
    }

    @Async
    public static GtfsInput createGtfsInput(byte[] file) throws IOException, URISyntaxException {
        return new GtfsZipFileInput(new ZipFile(new SeekableInMemoryByteChannel(file)));
    }

    @Async
    public static void printSummary(long startNanos, GtfsFeedContainer feedContainer) {
        final long endNanos = System.nanoTime();
        if (!feedContainer.isParsedSuccessfully()) {
            System.out.println(" ----------------------------------------- ");
            System.out.println("|       !!!    PARSING FAILED    !!!      |");
            System.out.println("|   Most validators were never invoked.   |");
            System.out.println("|   Please see report.json for details.   |");
            System.out.println(" ----------------------------------------- ");
        }
        double t = (endNanos - startNanos) / 1e9;
        System.out.printf("Validation took %.3f seconds%n", t);
        System.out.println(feedContainer.tableTotals());
    }

    @Async
    public static GtfsFeedContainer loadAndValidate(
            ValidatorLoader validatorLoader,
            GtfsFeedLoader feedLoader,
            NoticeContainer noticeContainer,
            GtfsInput gtfsInput,
            ValidationContext validationContext)
            throws InterruptedException {
        GtfsFeedContainer feedContainer;
        feedContainer =
                feedLoader.loadAndValidate(
                        gtfsInput,
                        new DefaultValidatorProvider(validationContext, validatorLoader),
                        noticeContainer);
        return feedContainer;
    }
}
