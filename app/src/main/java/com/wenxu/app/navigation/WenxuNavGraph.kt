package com.wenxu.app.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.wenxu.app.AppContainer
import com.wenxu.app.core.localization.applyAppLanguage
import com.wenxu.app.feature.categories.CategoriesScreen
import com.wenxu.app.feature.categories.CategoriesViewModel
import com.wenxu.app.feature.categories.CategoryDetailScreen
import com.wenxu.app.feature.categories.CategoryDetailViewModel
import com.wenxu.app.feature.documents.DocumentsScreen
import com.wenxu.app.feature.documents.DocumentsViewModel
import com.wenxu.app.feature.home.HomeScreen
import com.wenxu.app.feature.home.HomeViewModel
import com.wenxu.app.feature.language.LanguageScreen
import com.wenxu.app.feature.language.LanguageViewModel
import com.wenxu.app.feature.guide.FirstUseGuideScreen
import com.wenxu.app.feature.guide.FirstUseGuideViewModel
import com.wenxu.app.feature.onboarding.OnboardingScreen
import com.wenxu.app.feature.onboarding.OnboardingViewModel
import com.wenxu.app.feature.related.RelatedScreen
import com.wenxu.app.feature.related.RelatedViewModel
import com.wenxu.app.feature.settings.ScanSourcesScreen
import com.wenxu.app.feature.settings.ScanSourcesViewModel
import com.wenxu.app.feature.settings.SettingsScreen
import com.wenxu.app.feature.settings.SettingsViewModel
import com.wenxu.app.feature.trash.TrashScreen
import com.wenxu.app.feature.trash.TrashViewModel
import com.wenxu.app.feature.reader.PdfReaderScreen
import com.wenxu.app.feature.reader.PdfReaderViewModel
import com.wenxu.app.feature.importing.ImportScreen
import com.wenxu.app.feature.importing.ImportViewModel

@Composable
fun WenxuNavGraph(
    navController: NavHostController,
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val pendingImportUris by container.incomingIntentStore.pendingUris.collectAsStateWithLifecycle()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    LaunchedEffect(pendingImportUris, currentRoute) {
        val isPreparingFirstUse = currentRoute == WenxuDestinations.LanguageGate.route ||
            currentRoute == WenxuDestinations.Language.route ||
            currentRoute == WenxuDestinations.FirstUseGuide.route ||
            currentRoute == WenxuDestinations.Onboarding.route
        if (currentRoute != null && pendingImportUris.isNotEmpty() && !isPreparingFirstUse) {
            navController.navigate(WenxuDestinations.Import.route) { launchSingleTop = true }
        }
    }
    NavHost(
        navController = navController,
        startDestination = WenxuDestinations.LanguageGate.route,
        modifier = Modifier.padding(contentPadding),
    ) {
        composable(WenxuDestinations.LanguageGate.route) {
            val languageViewModel: LanguageViewModel = viewModel(
                factory = LanguageViewModel.factory(container.languagePreferences),
            )
            val languageState by languageViewModel.state.collectAsStateWithLifecycle()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
            LaunchedEffect(languageState.isLoading, languageState.savedLanguage) {
                if (!languageState.isLoading) {
                    languageState.savedLanguage?.let(::applyAppLanguage)
                    val destination = if (languageState.savedLanguage == null) {
                        WenxuDestinations.Language.route
                    } else {
                        WenxuDestinations.FirstUseGuide.route
                    }
                    navController.navigate(destination) {
                        if (languageState.savedLanguage != null) {
                            popUpTo(WenxuDestinations.LanguageGate.route) { inclusive = true }
                        }
                        launchSingleTop = true
                    }
                }
            }
        }
        composable(WenxuDestinations.Language.route) {
            val languageViewModel: LanguageViewModel = viewModel(
                factory = LanguageViewModel.factory(container.languagePreferences),
            )
            LanguageScreen(
                viewModel = languageViewModel,
                showBack = false,
                onBack = {},
                onConfirmed = {
                    navController.navigate(WenxuDestinations.FirstUseGuide.route) {
                        popUpTo(WenxuDestinations.LanguageGate.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(WenxuDestinations.FirstUseGuide.route) {
            val guideViewModel: FirstUseGuideViewModel = viewModel(
                factory = FirstUseGuideViewModel.factory(container.guidePreferences),
            )
            val guideState by guideViewModel.state.collectAsStateWithLifecycle()
            if (guideState.isLoading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            } else if (guideState.shouldShowGuide) {
                FirstUseGuideScreen(onFinished = guideViewModel::completeGuide)
            } else {
                LaunchedEffect(Unit) {
                    navController.navigate(WenxuDestinations.Onboarding.route) {
                        popUpTo(WenxuDestinations.FirstUseGuide.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
        }
        composable(WenxuDestinations.Onboarding.route) {
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.factory(
                    repository = container.sourceRepository,
                    storageAccessController = container.storageAccessController,
                    scanScheduler = container.scanScheduler,
                    scanPreferences = container.scanPreferences,
                ),
            )
            OnboardingScreen(
                viewModel = onboardingViewModel,
                storageAccessController = container.storageAccessController,
                onFilesSelected = { uris ->
                    container.incomingIntentStore.acceptUris(uris)
                    navController.navigate(WenxuDestinations.Import.route) {
                        popUpTo(WenxuDestinations.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onCompleted = {
                    val destination = if (pendingImportUris.isEmpty()) {
                        WenxuDestinations.Home.route
                    } else {
                        WenxuDestinations.Import.route
                    }
                    navController.navigate(destination) {
                        popUpTo(WenxuDestinations.Onboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(WenxuDestinations.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.factory(
                    repository = container.homeRepository,
                    sourceRepository = container.sourceRepository,
                    scanPreferences = container.scanPreferences,
                    scanScheduler = container.scanScheduler,
                    storageAccessController = container.storageAccessController,
                ),
            )
            HomeScreen(
                viewModel = homeViewModel,
                onOpenRelated = { navController.navigate(WenxuDestinations.Related.route) },
                onOpenUnclassified = { navController.navigate(WenxuDestinations.Unclassified) },
                onOpenAllDocuments = { navController.navigate(WenxuDestinations.Documents.route) },
                onOpenSettings = { navController.navigate(WenxuDestinations.Settings.route) },
                onOpenPdf = { documentId ->
                    navController.navigate(WenxuDestinations.pdfReader(documentId))
                },
            )
        }
        composable(WenxuDestinations.Documents.route) {
            val documentsViewModel: DocumentsViewModel = viewModel(
                factory = DocumentsViewModel.factory(
                    repository = container.documentsRepository,
                    trashController = container.trashService,
                    fileTransferController = container.fileTransferController,
                    feedbackController = container.similarityFeedbackController,
                ),
            )
            DocumentsScreen(
                viewModel = documentsViewModel,
                onOpenTrash = { navController.navigate(WenxuDestinations.Trash.route) },
                onOpenPdf = { documentId ->
                    navController.navigate(WenxuDestinations.pdfReader(documentId))
                },
                onOpenRelated = { targetIds ->
                    navController.navigate(WenxuDestinations.related(targetIds))
                },
            )
        }
        composable(WenxuDestinations.Categories.route) {
            val categoriesViewModel: CategoriesViewModel = viewModel(
                factory = CategoriesViewModel.factory(container.categoriesRepository),
            )
            CategoriesScreen(
                viewModel = categoriesViewModel,
                onOpenCategory = { categoryId ->
                    navController.navigate(
                        categoryId?.let { WenxuDestinations.categoryDetail(it) }
                            ?: WenxuDestinations.Unclassified,
                    )
                },
            )
        }
        composable(
            route = WenxuDestinations.CategoryDetailPattern,
            arguments = listOf(navArgument("categoryId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getLong("categoryId") ?: return@composable
            val detailViewModel: CategoryDetailViewModel = viewModel(
                factory = CategoryDetailViewModel.factory(container.categoriesRepository, categoryId),
            )
            CategoryDetailScreen(
                viewModel = detailViewModel,
                onBack = { navController.popBackStack() },
                onOpenPdf = { navController.navigate(WenxuDestinations.pdfReader(it)) },
            )
        }
        composable(WenxuDestinations.Unclassified) {
            val detailViewModel: CategoryDetailViewModel = viewModel(
                factory = CategoryDetailViewModel.factory(container.categoriesRepository, null),
            )
            CategoryDetailScreen(
                viewModel = detailViewModel,
                onBack = { navController.popBackStack() },
                onOpenPdf = { navController.navigate(WenxuDestinations.pdfReader(it)) },
            )
        }
        composable(
            route = WenxuDestinations.RelatedPattern,
            arguments = listOf(
                navArgument("targetIds") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val targetDocumentIds = WenxuDestinations.parseRelatedTarget(
                backStackEntry.arguments?.getString("targetIds"),
            )
            val relatedViewModel: RelatedViewModel = viewModel(
                factory = RelatedViewModel.factory(
                    repository = container.relatedRepository,
                    trashController = container.trashService,
                    feedbackController = container.similarityFeedbackController,
                    targetDocumentIds = targetDocumentIds,
                ),
            )
            RelatedScreen(
                viewModel = relatedViewModel,
                onBack = { navController.popBackStack() },
                onOpenPdf = { navController.navigate(WenxuDestinations.pdfReader(it)) },
            )
        }
        composable(WenxuDestinations.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    sourceRepository = container.sourceRepository,
                    preferences = container.scanPreferences,
                    languagePreferences = container.languagePreferences,
                    trashRepository = container.trashRepository,
                    storageAccessController = container.storageAccessController,
                ),
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenSources = { navController.navigate(WenxuDestinations.ScanSources.route) },
                onOpenTrash = { navController.navigate(WenxuDestinations.Trash.route) },
                onOpenHelp = { navController.navigate(WenxuDestinations.HelpGuide.route) },
                onOpenLanguage = {
                    navController.navigate(WenxuDestinations.SettingsLanguage.route)
                },
            )
        }
        composable(WenxuDestinations.SettingsLanguage.route) {
            val languageViewModel: LanguageViewModel = viewModel(
                factory = LanguageViewModel.factory(container.languagePreferences),
            )
            LanguageScreen(
                viewModel = languageViewModel,
                showBack = true,
                onBack = { navController.popBackStack() },
                onConfirmed = { navController.popBackStack() },
            )
        }
        composable(WenxuDestinations.HelpGuide.route) {
            FirstUseGuideScreen(
                helpMode = true,
                onFinished = { navController.popBackStack() },
            )
        }
        composable(WenxuDestinations.ScanSources.route) {
            val settingsViewModel: ScanSourcesViewModel = viewModel(
                factory = ScanSourcesViewModel.factory(
                    repository = container.sourceRepository,
                    storageAccessController = container.storageAccessController,
                    preferences = container.scanPreferences,
                    scanSelectedFolders = container.homeRepository::rescan,
                ),
            )
            ScanSourcesScreen(
                viewModel = settingsViewModel,
                storageAccessController = container.storageAccessController,
                onFilesSelected = container.incomingIntentStore::acceptUris,
                onBack = { navController.popBackStack() },
            )
        }
        composable(WenxuDestinations.Trash.route) {
            val trashViewModel: TrashViewModel = viewModel(
                factory = TrashViewModel.factory(container.trashRepository),
            )
            TrashScreen(viewModel = trashViewModel, onBack = { navController.popBackStack() })
        }
        composable(WenxuDestinations.Import.route) {
            val importViewModel: ImportViewModel = viewModel(
                factory = ImportViewModel.factory(
                    intentStore = container.incomingIntentStore,
                    metadataReader = container.incomingMetadataReader,
                    preferences = container.inboxPreferences,
                    service = container.inboxImportService,
                ),
            )
            ImportScreen(
                viewModel = importViewModel,
                onCompleted = {
                    navController.navigate(WenxuDestinations.Unclassified) {
                        popUpTo(WenxuDestinations.Import.route) { inclusive = true }
                    }
                },
                onCancelled = { navController.popBackStack() },
            )
        }
        composable(
            route = WenxuDestinations.PdfReaderPattern,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getLong("documentId") ?: return@composable
            val readerViewModel: PdfReaderViewModel = viewModel(
                factory = PdfReaderViewModel.factory(
                    documentId = documentId,
                    repository = container.pdfReaderRepository,
                ),
            )
            PdfReaderScreen(viewModel = readerViewModel, onBack = { navController.popBackStack() })
        }
    }
}
