package com.wenxu.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.room.Room
import com.wenxu.app.core.database.WenxuDatabase
import com.wenxu.app.core.database.MIGRATION_1_2
import com.wenxu.app.core.database.MIGRATION_2_3
import com.wenxu.app.core.database.MIGRATION_3_4
import com.wenxu.app.core.database.MIGRATION_4_5
import com.wenxu.app.core.database.MIGRATION_5_6
import com.wenxu.app.core.index.DocumentIndexer
import com.wenxu.app.core.index.MediaStoreDocumentPageSource
import com.wenxu.app.core.index.ProgressiveScanEngine
import com.wenxu.app.core.index.ProgressiveScanWorkerRunner
import com.wenxu.app.core.index.RoomDocumentIndexStore
import com.wenxu.app.core.index.RoomProgressiveScanStore
import com.wenxu.app.core.index.ScanCoordinator
import com.wenxu.app.core.index.ScanScheduler
import com.wenxu.app.core.index.WenxuWorkerFactory
import com.wenxu.app.core.index.WorkManagerScanScheduler
import com.wenxu.app.core.permission.StorageAccessController
import com.wenxu.app.core.permission.StorageAccessState
import com.wenxu.app.core.storage.AndroidDocumentGateway
import com.wenxu.app.core.storage.RoomScanSourceRepository
import com.wenxu.app.core.storage.ScanSourceRepository
import com.wenxu.app.feature.home.HomeRepository
import com.wenxu.app.feature.home.RoomHomeRepository
import com.wenxu.app.feature.documents.DocumentsRepository
import com.wenxu.app.feature.documents.RoomDocumentsRepository
import com.wenxu.app.feature.categories.CategoriesRepository
import com.wenxu.app.feature.categories.RoomCategoriesRepository
import com.wenxu.app.feature.related.RelatedRepository
import com.wenxu.app.feature.related.RoomRelatedRepository
import com.wenxu.app.core.trash.RoomTrashStore
import com.wenxu.app.core.trash.AndroidSystemTrashGateway
import com.wenxu.app.core.trash.TrashRequestPlanner
import com.wenxu.app.core.trash.TrashService
import com.wenxu.app.feature.trash.RoomTrashRepository
import com.wenxu.app.feature.trash.TrashRepository
import com.wenxu.app.core.reader.AndroidPdfReaderRepository
import com.wenxu.app.core.reader.PdfReaderRepository
import com.wenxu.app.core.storage.FileTransferController
import com.wenxu.app.core.storage.FileTransferService
import com.wenxu.app.core.storage.RoomFileTransferStore
import com.wenxu.app.core.settings.DataStoreWenxuPreferences
import com.wenxu.app.core.settings.InboxPreferences
import com.wenxu.app.core.settings.GuidePreferences
import com.wenxu.app.core.settings.LanguagePreferences
import com.wenxu.app.core.importing.AndroidIncomingMetadataReader
import com.wenxu.app.core.importing.InboxImportService
import com.wenxu.app.core.importing.IncomingIntentStore
import com.wenxu.app.core.importing.IncomingMetadataReader
import com.wenxu.app.core.settings.ScanPreferences
import com.wenxu.app.core.relations.ContentHasher
import com.wenxu.app.core.relations.DocumentFingerprintStore
import com.wenxu.app.core.relations.ExactDuplicateDetector
import com.wenxu.app.core.relations.MinHashFingerprint
import com.wenxu.app.core.relations.OoxmlDocumentFeatureExtractor
import com.wenxu.app.core.relations.PersistentFingerprintSecretProvider
import com.wenxu.app.core.relations.RelationAnalysisCoordinator
import com.wenxu.app.core.relations.RelationAnalysisScheduler
import com.wenxu.app.core.relations.RelationAnalysisWorkerRunner
import com.wenxu.app.core.relations.RoomRelationAnalysisScheduleStore
import com.wenxu.app.core.relations.RoomDocumentFingerprintPersistence
import com.wenxu.app.core.relations.RoomExactDuplicateStore
import com.wenxu.app.core.relations.RoomNameSimilarityStore
import com.wenxu.app.core.relations.SharedPreferencesFingerprintSecretStorage
import com.wenxu.app.core.relations.SimilarityFeedbackController
import com.wenxu.app.core.relations.SimilarityFeedbackService

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = DataStoreWenxuPreferences(applicationContext)
    val scanPreferences: ScanPreferences = preferences
    val inboxPreferences: InboxPreferences = preferences
    val guidePreferences: GuidePreferences = preferences
    val languagePreferences: LanguagePreferences = preferences
    val incomingIntentStore = IncomingIntentStore()
    val incomingMetadataReader: IncomingMetadataReader = AndroidIncomingMetadataReader(
        applicationContext.contentResolver,
    )
    val database: WenxuDatabase = Room.databaseBuilder(
        applicationContext,
        WenxuDatabase::class.java,
        "wenxu.db",
    ).addMigrations(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    )
        .build()

    val gateway = AndroidDocumentGateway(applicationContext)
    private val fingerprintMinHash = MinHashFingerprint(
        PersistentFingerprintSecretProvider(
            SharedPreferencesFingerprintSecretStorage(applicationContext),
        ),
    )
    private val nameSimilarityStore = RoomNameSimilarityStore(database)
    private val fingerprintStore = DocumentFingerprintStore(
        persistence = RoomDocumentFingerprintPersistence(database.relationAnalysisDao()),
        extractor = OoxmlDocumentFeatureExtractor(gateway, fingerprintMinHash),
    )
    val relationAnalysisCoordinator = RelationAnalysisCoordinator(
        exactDuplicateAnalysis = ExactDuplicateDetector(
            store = RoomExactDuplicateStore(database),
            hasher = ContentHasher(gateway),
        ),
        store = nameSimilarityStore,
        fingerprintProvider = fingerprintStore,
        minHash = fingerprintMinHash,
    )
    private val relationAnalysisScheduleStore = RoomRelationAnalysisScheduleStore(database)
    val relationAnalysisScheduler = RelationAnalysisScheduler(applicationContext, database)
    val similarityFeedbackController: SimilarityFeedbackController = SimilarityFeedbackService(
        database = database,
        reconcileQueued = { generation ->
            if (generation == null) {
                relationAnalysisScheduler.retryQueued()
            } else {
                relationAnalysisScheduler.enqueueExistingQueued(generation)
            }
        },
    )
    val relationAnalysisWorkerRunner = RelationAnalysisWorkerRunner(
        store = relationAnalysisScheduleStore,
        analysis = relationAnalysisCoordinator::run,
    )
    private val trashStore = RoomTrashStore(database)
    val trashService = TrashService(
        store = trashStore,
        gateway = gateway,
        relationRequest = relationAnalysisScheduler::request,
        systemGateway = AndroidSystemTrashGateway(applicationContext.contentResolver),
        planner = TrashRequestPlanner(Build.VERSION.SDK_INT),
    )
    val trashRepository: TrashRepository = RoomTrashRepository(trashStore, trashService)
    val pdfReaderRepository: PdfReaderRepository = AndroidPdfReaderRepository(
        resolver = applicationContext.contentResolver,
        database = database,
    )
    val sourceRepository: ScanSourceRepository = RoomScanSourceRepository(
        sourceDao = database.sourceDao(),
        gateway = gateway,
    )
    val fileTransferController: FileTransferController = FileTransferService(
        store = RoomFileTransferStore(database),
        sourceRepository = sourceRepository,
        gateway = gateway,
    )

    private val documentIndexer = DocumentIndexer(RoomDocumentIndexStore(database))
    private val scanCoordinator = ScanCoordinator(
        sourceDao = database.sourceDao(),
        gateway = gateway,
        indexer = documentIndexer,
        relationRequest = relationAnalysisScheduler::request,
    )

    val storageAccessController = StorageAccessController(applicationContext)
    private val progressiveScanStore = RoomProgressiveScanStore(database)
    val scanScheduler: ScanScheduler = WorkManagerScanScheduler(
        context = applicationContext,
        store = progressiveScanStore,
        storageAccessController = storageAccessController,
    )
    val progressiveScanWorkerRunner = ProgressiveScanWorkerRunner(
        store = progressiveScanStore,
        chunkRunnerProvider = ::progressiveScanEngineOrNull,
        accessState = storageAccessController::state,
        relationRequest = relationAnalysisScheduler::request,
    )
    val workerFactory = WenxuWorkerFactory(
        runnerProvider = { progressiveScanWorkerRunner },
        relationRunnerProvider = { relationAnalysisWorkerRunner },
    )

    val homeRepository: HomeRepository = RoomHomeRepository(
        database = database,
        scanCoordinator = scanCoordinator,
    )
    val inboxImportService = InboxImportService(
        gateway = gateway,
        sourceRepository = sourceRepository,
        preferences = inboxPreferences,
        homeRepository = homeRepository,
    )

    val documentsRepository: DocumentsRepository = RoomDocumentsRepository(database)
    val categoriesRepository: CategoriesRepository = RoomCategoriesRepository(database)
    val relatedRepository: RelatedRepository = RoomRelatedRepository(database)

    @SuppressLint("NewApi")
    fun progressiveScanEngineOrNull(): ProgressiveScanEngine? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (storageAccessController.state() != StorageAccessState.Granted) return null
        return ProgressiveScanEngine(
            pageSource = MediaStoreDocumentPageSource(
                contentResolver = applicationContext.contentResolver,
                storageAccessController = storageAccessController,
            ),
            indexer = documentIndexer,
            store = progressiveScanStore,
        )
    }
}
