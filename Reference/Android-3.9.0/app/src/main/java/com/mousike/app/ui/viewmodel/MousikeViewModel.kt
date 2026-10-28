package com.mousike.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mousike.app.data.catalog.MusicCatalog
import com.mousike.app.data.model.Album
import com.mousike.app.data.model.Artist
import com.mousike.app.data.model.PlaybackState
import com.mousike.app.data.model.RepeatMode
import com.mousike.app.data.model.Track
import com.mousike.app.data.model.UserPlaylist
import com.mousike.app.data.repository.MusicRepository
import com.mousike.app.data.repository.RecommendationResult
import com.mousike.app.data.repository.SearchResults
import com.mousike.app.playback.AudioPlayerEngine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.mousike.app.settings.AppSettings
import com.mousike.app.settings.Preferences
import com.mousike.app.album.AlbumDetector
import com.mousike.app.download.DownloadStore
import com.mousike.app.playback.InnerTubeExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class NavScreen {
    HOME,
    SEARCH,
    EXPLORE,
    LIBRARY,
    SETTINGS,
    ABOUT,
    MIX_BUILD,
    TASTE_BUILD,
    PLAYLIST_DETAIL,
    ALBUM_DETAIL,
    ARTIST_DETAIL,
    FULL_PLAYER,
    LYRICS,
    QUEUE,
    DOWNLOADS
}

@OptIn(FlowPreview::class)
class MousikeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val playerEngine = AudioPlayerEngine.getInstance(application)
    private val sessionQueue=com.mousike.app.session.AndroidSessionQueue.get(application)
    val queueSessionStatus=sessionQueue.status

    val settings = AppSettings.get(application)
    val preferences = settings.state
    private val qualityBinding=com.mousike.app.settings.QualitySelectionBinding(playerEngine.player,preferences)
    val downloads = DownloadStore.get(application).state
    private val _albumLoading = MutableStateFlow(false)
    val albumLoading: StateFlow<Boolean> = _albumLoading
    private val _inspection = MutableStateFlow<String?>(null)
    val inspection: StateFlow<String?> = _inspection
    private var inspectJob: Job? = null
    private var tapVersion=0L
    private var recommendationsJob: Job? = null
    private var mixJob: Job?=null
    private var onboardingJob: Job?=null
    private val _assemblyStage=MutableStateFlow<Int?>(null)
    val assemblyStage: StateFlow<Int?> = _assemblyStage
    private val _mixStage=MutableStateFlow(0)
    val mixStage: StateFlow<Int> = _mixStage
    private val _mixTitle=MutableStateFlow("Daily Mix")
    val mixTitle: StateFlow<String> = _mixTitle
    private val _mixError=MutableStateFlow<String?>(null)
    val mixError: StateFlow<String?> = _mixError
    private var mixNumber=1
    private val _searchError=MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError
    private var preferencesJob: Job? = null
    val playbackState: StateFlow<PlaybackState> = playerEngine.playbackState
    // Browsing lists need selection and transport state, not four progress updates per second.
    val browsingPlaybackState: StateFlow<PlaybackState> = playbackState
        .map {it.copy(currentPositionMs=0,sleepTimerRemainingSec=0)}.distinctUntilChanged()
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),PlaybackState())
    val visualizerAmplitudes: StateFlow<List<Float>> = playerEngine.visualizerAmplitudes

    // Navigation Stack
    private val _currentScreen = MutableStateFlow(NavScreen.HOME)
    val currentScreen: StateFlow<NavScreen> = _currentScreen.asStateFlow()

    private val navStack = mutableListOf<NavScreen>()

    // Detail screens state
    private val _selectedPlaylist = MutableStateFlow<UserPlaylist?>(null)
    val selectedPlaylist: StateFlow<UserPlaylist?> = _selectedPlaylist.asStateFlow()

    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    private val _selectedArtist = MutableStateFlow<Artist?>(null)
    val selectedArtist: StateFlow<Artist?> = _selectedArtist.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>(null)
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

    // Library & User Data
    val likedTrackIds: StateFlow<Set<String>> = repository.likedTrackIdsFlow
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    val likedTracks: StateFlow<List<Track>> = repository.likedTracksFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<UserPlaylist>> = repository.playlistsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentlyPlayed: StateFlow<List<Track>> = repository.recentlyPlayedTracksFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val savedAlbumIds: StateFlow<List<String>> = repository.savedAlbumIdsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val savedArtistIds: StateFlow<List<String>> = repository.savedArtistIdsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow(SearchResults())
    private val _catalogStatus = MutableStateFlow("Loading music…")
    val catalogStatus: StateFlow<String> = _catalogStatus.asStateFlow()
    private val _catalogVersion = MutableStateFlow(0)
    val catalogVersion: StateFlow<Int> = _catalogVersion.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    val searchResults: StateFlow<SearchResults> = _searchResults.asStateFlow()

    val searchHistory: StateFlow<List<String>> = repository.searchHistoryFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Recommendations
    private val _recommendations = MutableStateFlow(RecommendationResult())
    val recommendations: StateFlow<RecommendationResult> = _recommendations.asStateFlow()
    private val _homeFeed=MutableStateFlow(com.mousike.app.recommendation.HomeFeed())
    val homeFeed: StateFlow<com.mousike.app.recommendation.HomeFeed> = _homeFeed
    private var homeExpansionJob: Job?=null
    fun expandHome() {
        if(homeExpansionJob?.isActive==true)return
        homeExpansionJob=viewModelScope.launch {
            repository.expandHomeCatalog {withContext(Dispatchers.Main){_catalogVersion.value++;loadRecommendations()}}
        }
    }

    // Modals / Dialogs
    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog: StateFlow<Boolean> = _showCreatePlaylistDialog.asStateFlow()

    private val _trackToAddToPlaylist = MutableStateFlow<Track?>(null)
    val trackToAddToPlaylist: StateFlow<Track?> = _trackToAddToPlaylist.asStateFlow()

    private val _showSleepTimerDialog = MutableStateFlow(false)
    val showSleepTimerDialog: StateFlow<Boolean> = _showSleepTimerDialog.asStateFlow()

    private val _showEqualizerDialog = MutableStateFlow(false)
    val showEqualizerDialog: StateFlow<Boolean> = _showEqualizerDialog.asStateFlow()

    private val _showFeedbackDialogForTrack = MutableStateFlow<Track?>(null)
    val showFeedbackDialogForTrack: StateFlow<Track?> = _showFeedbackDialogForTrack.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        viewModelScope.launch {sessionQueue.notices.collect {showSnackbar(it)}}
        // The service records listening activity even after the activity is destroyed.
        playerEngine.onTrackStarted = { loadRecommendations() }
        playerEngine.onTrackCompleted = { loadRecommendations() }
        playerEngine.onTrackSkipped = { _,_ -> loadRecommendations() }

        // Live debounced search
        viewModelScope.launch {
            _searchQuery
                .debounce(250)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _searchResults.value = SearchResults();_isSearching.value=false
                    } else {
                        _isSearching.value = true;_searchError.value=null
                        try {
                            _searchResults.value = repository.remoteSearch(query)
                            _catalogVersion.value += 1
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Exception) {
                            // Do not substitute fictional music if the remote catalog is unavailable.
                            _searchResults.value = repository.searchAll(query)
                            _searchError.value="Couldn’t reach the music catalog. Check your connection and retry."
                            _catalogStatus.value = "Search unavailable: ${e.message ?: "network error"}"
                        } finally {
                            if(_searchQuery.value==query)_isSearching.value = false
                        }
                    }
                }
        }

        viewModelScope.launch {
            repository.restoreCatalog()
            _catalogVersion.value++
            loadRecommendations();refreshYouTubeMusic()
        }
    }

    fun refreshYouTubeMusic() {
        viewModelScope.launch {
            _catalogStatus.value = "Loading real YouTube Music catalog…"
            val result = repository.refreshCatalog()
            result.onSuccess { count ->
                _catalogVersion.value += 1
                _catalogStatus.value = if(com.mousike.app.RuntimeMode.offlineFixture) "Offline sound check • bundled originals" else if (count > 0) "Connected to YouTube Music" else "Catalog returned no songs — try Search"
                loadRecommendations()
            }.onFailure { e ->
                _catalogStatus.value = "YouTube Music unavailable: ${e.message ?: "network error"}"
            }
        }
    }

    fun refreshExploreMusic() {
        viewModelScope.launch {
            repository.refreshExplore().onSuccess {
                _catalogVersion.value += 1
                loadRecommendations()
            }.onFailure { e -> _catalogStatus.value = "Explore unavailable: ${e.message}" }
        }
    }

    private var tasteActivity=com.mousike.app.ui.personalization.TasteActivity(emptySet(),emptyList(),emptyList())
    private val _recommendedArtists=MutableStateFlow<List<Artist>>(emptyList())
    val recommendedArtists: StateFlow<List<Artist>> = _recommendedArtists
    private var artistDiscoveryJob: Job?=null
    private var lastArtistDiscoveryKey=""
    private val _artistDiscoveryLoading=MutableStateFlow(false)
    val artistDiscoveryLoading: StateFlow<Boolean> = _artistDiscoveryLoading
    private val _artistDiscoveryError=MutableStateFlow<String?>(null)
    val artistDiscoveryError: StateFlow<String?> = _artistDiscoveryError
    fun discoverRecommendedArtists(force: Boolean=false) {
        val p=preferences.value
        val key=(p.languages.sorted()+p.artists.sorted()).joinToString("|")
        if(artistDiscoveryJob?.isActive==true || !force && key==lastArtistDiscoveryKey)return
        artistDiscoveryJob=viewModelScope.launch {
            _artistDiscoveryLoading.value=true;_artistDiscoveryError.value=null
            try {
                val seeds=com.mousike.app.ui.personalization.ArtistSuggestions.seeds(p.languages,p.artists,8)
                for(batch in seeds.chunked(2)) {
                    kotlinx.coroutines.coroutineScope {batch.map {seed->async {
                        try {repository.remoteSearch(seed)} catch(e: kotlinx.coroutines.CancellationException){throw e} catch(_: Exception){null}
                    }}.forEach {it.await()}}
                    _catalogVersion.value++
                    refreshPersonalPresentation()
                }
                if(_recommendedArtists.value.isNotEmpty())lastArtistDiscoveryKey=key
                else _artistDiscoveryError.value="Couldn’t load artist profiles. Check your connection and try again."
            } catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(_: Exception){_artistDiscoveryError.value="Couldn’t load artist profiles. Please try again."}
            finally {_artistDiscoveryLoading.value=false}
        }
    }
    private suspend fun refreshPersonalPresentation() {
        val activity=com.mousike.app.ui.personalization.TasteActivityReader.read(getApplication())
        val catalog=MusicCatalog.TRACKS;val artists=MusicCatalog.ARTISTS;val p=preferences.value
        val saved=repository.savedArtistIdsFlow.first().toSet()
        val result=withContext(Dispatchers.Default) {
            com.mousike.app.ui.personalization.ArtistSuggestions.rank(artists,catalog,activity,p,saved)
        }
        tasteActivity=activity;_recommendedArtists.value=result
    }
    fun playHomeTrack(track: Track)=playTrack(track,mode=com.mousike.app.session.ListeningMode.HOME)
    fun playSearchTrack(track: Track)=playTrack(track,mode=com.mousike.app.session.ListeningMode.SEARCH)
    fun playQueueTrack(track: Track,queue: List<Track>,index: Int)=playTrack(track,queue,index,keepSession=sessionQueue.status.value.mode!=null)

    fun loadRecommendations() {
        recommendationsJob?.cancel()
        recommendationsJob=viewModelScope.launch {
            kotlinx.coroutines.delay(180)
            val local=repository.generateRecommendations()
            _recommendations.value=local
            _homeFeed.value=repository.homeFeed()
            refreshPersonalPresentation()
            if(preferences.value.aiEnabled) _recommendations.value=repository.aiBoost(local)
        }
    }

    // Greeting based on real time
    fun getDynamicGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Morning, early bird"
            in 12..16 -> "Your afternoon rhythm"
            in 17..21 -> "Evening, ${preferences.value.name}"
            else -> "Still up, night owl?"
        }
    }

    // Navigation methods
    fun navigateTo(screen: NavScreen) {
        if (_currentScreen.value != screen) {
            if(navStack.size>=32)navStack.removeAt(0)
            navStack.add(_currentScreen.value)
            _currentScreen.value = screen
            if(screen==NavScreen.HOME)expandHome()
        }
    }

    fun navigateBack(): Boolean {
        if(_currentScreen.value==NavScreen.MIX_BUILD)mixJob?.cancel()
        if(_currentScreen.value==NavScreen.TASTE_BUILD)tasteJob?.cancel()
        if (navStack.isNotEmpty()) {
            _currentScreen.value = navStack.removeAt(navStack.lastIndex)
            return true
        }
        if (_currentScreen.value != NavScreen.HOME) {
            _currentScreen.value = NavScreen.HOME
            return true
        }
        return false
    }

    fun openPlaylist(playlist: UserPlaylist) {
        _selectedPlaylist.value = playlist
        navigateTo(NavScreen.PLAYLIST_DETAIL)
    }

    fun openAlbum(album: Album) {
        cancelInspection();val token=tapVersion
        _selectedAlbum.value = album
        navigateTo(NavScreen.ALBUM_DETAIL)
        inspectJob=viewModelScope.launch {
            _albumLoading.value=true
            try {
                val loaded=repository.loadAlbum(album)
                if(_selectedAlbum.value?.id==album.id) _selectedAlbum.value=loaded
                InnerTubeExtractor.get(getApplication()).prefetch(loaded.trackIds.mapNotNull {MusicCatalog.getTrackById(it)})
                _catalogVersion.value += 1
            } catch(e: Exception) { showSnackbar("Album tracks unavailable: ${e.message}") }
            finally {if(token==tapVersion)_albumLoading.value=false}
        }
    }

    fun openArtist(artist: Artist) {
        _selectedArtist.value = artist
        navigateTo(NavScreen.ARTIST_DETAIL)
        viewModelScope.launch {
            try {
                _selectedArtist.value = repository.loadArtist(artist)
                _catalogVersion.value += 1
            } catch (e: Exception) { showSnackbar("Artist tracks unavailable: ${e.message}") }
        }
    }

    fun openGenre(genre: String) {
        _selectedGenre.value = genre
        _searchQuery.value = genre
        navigateTo(NavScreen.SEARCH)
    }

    fun openFullPlayer() {
        navigateTo(NavScreen.FULL_PLAYER)
    }

    fun openLyrics() {
        navigateTo(NavScreen.LYRICS)
    }

    fun openQueue() {
        navigateTo(NavScreen.QUEUE)
    }

    // Playback Controls
    fun playTrack(track: Track, queue: List<Track> = emptyList(), startIndex: Int = -1, mode: com.mousike.app.session.ListeningMode?=null, keepSession: Boolean=false) {
        inspectJob?.cancel();val token=++tapVersion
        _inspection.value=null;_albumLoading.value=false
        val inspect=track.endMs==null && track.streamUrl.isBlank() && preferences.value.detectAlbums
        if(inspect && (AlbumDetector.isLong(track) || Regex("(?i)full album|jukebox|compilation").containsMatchIn(track.title))) {
            openLongVideo(track,token);return
        }
        // Select and start immediately. An unknown duration must not block playback.
        val effectiveMode=mode ?: if(_currentScreen.value==NavScreen.ALBUM_DETAIL && (_selectedAlbum.value?.confidence=="personal" || _selectedAlbum.value?.id?.startsWith("personal-")==true))com.mousike.app.session.ListeningMode.HOME else null
        val planned=when {effectiveMode!=null->sessionQueue.begin(effectiveMode,track);keepSession->queue;else->sessionQueue.begin(com.mousike.app.session.ListeningMode.HOME,track,queue.ifEmpty {listOf(track)})}
        playerEngine.playTrack(track,planned,startIndex)
        if(inspect && track.durationSec<=0)inspectJob=viewModelScope.launch {
            try {
                val ready=playbackState.filter {it.currentTrack?.id==track.id && (it.isPlaying || it.error!=null)}.first()
                if(ready.error!=null)return@launch
                val hydrated=repository.hydrateMetadata(track)
                if(token!=tapVersion)return@launch
                MusicCatalog.putTracks(listOf(hydrated));playerEngine.updateTrackMetadata(hydrated)
                if(AlbumDetector.isLong(hydrated) && playbackState.value.currentTrack?.id==track.id) {
                    playerEngine.pause();openLongVideo(hydrated,token)
                }
            } catch(e: kotlinx.coroutines.CancellationException) {throw e} catch(_: Exception) { /* Streaming continues with catalog metadata. */ }
        }
    }
    private fun openLongVideo(track: Track,token: Long) {
        val cached=repository.cachedVideoAlbum(track)
        MusicCatalog.putTracks(listOf(track))
        _selectedAlbum.value=cached ?: Album("video-${track.videoId}",track.title,track.artist,track.coverUrl,track.year,track.genre,emptyList(),track.videoId,"Finding the track list…","inspecting")
        navigateTo(NavScreen.ALBUM_DETAIL)
        if(cached!=null)return
        _albumLoading.value=true
        inspectJob=viewModelScope.launch {
            try {
                val album=repository.resolveLongVideo(track) { partial ->
                    if(token==tapVersion && _selectedAlbum.value?.id==partial.id) {
                        _selectedAlbum.value=partial;_albumLoading.value=false;_catalogVersion.value++
                    }
                }
                if(token==tapVersion) {_selectedAlbum.value=album;_catalogVersion.value++}
                InnerTubeExtractor.get(getApplication()).prefetch(album.trackIds.mapNotNull {MusicCatalog.getTrackById(it)})
            } catch(e: kotlinx.coroutines.CancellationException) {throw e}
            catch(e: Exception) {if(token==tapVersion)showSnackbar("Could not inspect this source. Check your connection and retry.")}
            finally {if(token==tapVersion){_inspection.value=null;_albumLoading.value=false}}
        }
    }
    fun cancelInspection() {tapVersion++;inspectJob?.cancel();_inspection.value=null;_albumLoading.value=false}
    fun playFullRecording() {_selectedAlbum.value?.sourceVideoId?.let { id -> MusicCatalog.getTrackById(id)?.let {playerEngine.playTrack(it,sessionQueue.begin(com.mousike.app.session.ListeningMode.HOME,it,listOf(it)))}}}
    fun applyAlbumTracklist(text: String) {val album=_selectedAlbum.value ?: return
        cancelInspection()
        viewModelScope.launch {runCatching {repository.applyTracklist(album,text)}.onSuccess {_selectedAlbum.value=it;_catalogVersion.value++}
            .onFailure {showSnackbar(it.message ?: "Invalid track list")}}
    }
    fun updatePreferences(transform: (Preferences)->Preferences) {
        val before=preferences.value
        settings.update(transform)
        val after=preferences.value
        if(before.languages!=after.languages || before.genres!=after.genres || before.artists!=after.artists || before.discovery!=after.discovery || before.aiEnabled!=after.aiEnabled || before.model!=after.model || before.musicFirst!=after.musicFirst) {
            preferencesJob?.cancel();preferencesJob=viewModelScope.launch {kotlinx.coroutines.delay(400);loadRecommendations()}
        }
    }
    fun completeOnboarding(name: String,languages: Set<String>,genres: Set<String>,artists: Set<String>) {
        onboardingArtistsJob?.cancel()
        settings.update {it.copy(name=name.ifBlank {"Listener"},languages=languages,genres=genres,artists=artists,onboarded=true)}
        viewModelScope.launch {runCatching {repository.seedPreferences()};_catalogVersion.value++;loadRecommendations();expandHome()}
    }
    fun editOnboarding() {settings.update {it.copy(onboarded=false)}}
    fun retrySearch() {
        val query=_searchQuery.value;if(query.isBlank())return
        _isSearching.value=true;_searchError.value=null
        viewModelScope.launch {
            try {val results=repository.remoteSearch(query);if(_searchQuery.value==query)_searchResults.value=results}
            catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(e: Exception){if(_searchQuery.value==query)_searchError.value="Couldn’t reach the music catalog. Check your connection and retry."}
            finally {if(_searchQuery.value==query)_isSearching.value=false}
        }
    }
    private var activeMix: com.mousike.app.ui.components.PersonalMix?=null
    fun retryDailyMix() {activeMix?.let {buildPersonalMix(it)} ?: buildDailyMix(mixNumber)}
    fun buildDailyMix(number: Int) {
        mixNumber=number
        buildPersonalMix(com.mousike.app.ui.components.PersonalMixes[if(number==1)0 else 1])
    }
    fun buildPersonalMix(mix: com.mousike.app.ui.components.PersonalMix) {
        mixJob?.cancel();activeMix=mix;_mixTitle.value=mix.title;_mixError.value=null
        navigateTo(NavScreen.MIX_BUILD)
        mixJob=viewModelScope.launch {
            try {
                if(MusicCatalog.TRACKS.isEmpty())repository.seedPreferences()
                val recommendations=repository.generateRecommendations()
                val selected=withContext(Dispatchers.Default) {com.mousike.app.ui.components.personalMixTracks(mix,recommendations,MusicCatalog.TRACKS)}
                if(selected.isEmpty())throw IllegalStateException("Search for a favourite artist to add more ${mix.title} songs, then try again.")
                val id=when(mix.id){"daily-1"->"personal-daily-mix-1";"daily-2"->"personal-daily-mix-2";else->"personal-collection-${mix.id}"}
                val album=Album(id,mix.title,"Made for ${preferences.value.name}",selected.first().coverUrl,java.time.LocalDate.now().year,"Personal mix",selected.map {it.id},resolutionNote="Your collection is ready. Pick a song when you want to listen.")
                MusicCatalog.putAlbums(listOf(album));com.mousike.app.data.catalog.CatalogCache.saveSoon(getApplication())
                if(_currentScreen.value==NavScreen.MIX_BUILD){_selectedAlbum.value=album;_catalogVersion.value++;_currentScreen.value=NavScreen.ALBUM_DETAIL}
            } catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(e: Exception){_mixError.value=e.message ?: "Couldn’t build this mix. Please retry."}
        }
    }
    private var tasteJob: Job?=null
    private val _tasteStage=MutableStateFlow(0)
    val tasteStage: StateFlow<Int> = _tasteStage
    private val _tasteError=MutableStateFlow<String?>(null)
    val tasteError: StateFlow<String?> = _tasteError
    private val _tasteResult=MutableStateFlow<com.mousike.app.ui.personalization.TasteAlbumResult?>(null)
    val tasteResult: StateFlow<com.mousike.app.ui.personalization.TasteAlbumResult?> = _tasteResult
    fun buildMyTaste() {
        tasteJob?.cancel();_tasteStage.value=0;_tasteError.value=null
        navigateTo(NavScreen.TASTE_BUILD)
        tasteJob=viewModelScope.launch {
            try {
                val start=android.os.SystemClock.elapsedRealtime()
                val activity=com.mousike.app.ui.personalization.TasteActivityReader.read(getApplication())
                _tasteStage.value=1
                if(MusicCatalog.TRACKS.isEmpty())repository.seedPreferences()
                val result=withContext(Dispatchers.Default) {com.mousike.app.ui.personalization.TasteAlbumBuilder.build(MusicCatalog.TRACKS,activity,preferences.value)}
                if(result.tracks.isEmpty())throw IllegalStateException("Choose a few artists or search for music first. Your Taste album will grow as you listen.")
                _tasteResult.value=result;_tasteStage.value=2
                val id=com.mousike.app.ui.personalization.TasteAlbumBuilder.ALBUM_ID
                val note=if(result.hasActivity) "Made from ${result.likes} likes, ${result.completions} completed listens and ${result.earlySkips} early skips in your saved activity. Artists, languages and available mood and regional music metadata shape this collection." else "A starting point from your chosen artists, languages and sounds. Complete songs, like favourites and skip what doesn’t fit to make the next build more personal."
                val album=Album(id,"Build My Taste","Made for ${preferences.value.name}",result.tracks.first().coverUrl,java.time.LocalDate.now().year,"Your Taste",result.tracks.map {it.id},resolutionNote=note,confidence="personal")
                MusicCatalog.pinTracks(result.tracks.map {it.id});MusicCatalog.putTracks(result.tracks);MusicCatalog.putAlbums(listOf(album))
                if(id !in repository.savedAlbumIdsFlow.first())repository.toggleSaveAlbum(id)
                MusicCatalog.pinAlbums(listOf(id));com.mousike.app.data.catalog.CatalogCache.saveSoon(getApplication());loadRecommendations()
                if(!preferences.value.reduceMotion)kotlinx.coroutines.delay((900-(android.os.SystemClock.elapsedRealtime()-start)).coerceAtLeast(0))
                if(_currentScreen.value==NavScreen.TASTE_BUILD){_selectedAlbum.value=album;_catalogVersion.value++;_currentScreen.value=NavScreen.ALBUM_DETAIL}
            } catch(e: kotlinx.coroutines.CancellationException){throw e}
            catch(e: Exception){_tasteError.value=e.message ?: "Couldn’t build your album. Please retry."}
        }
    }
    private var onboardingArtistsJob: Job?=null
    private var artistGeneration=0
    private val _onboardingArtists=MutableStateFlow<List<Artist>>(emptyList())
    val onboardingArtists: StateFlow<List<Artist>> = _onboardingArtists
    private val _onboardingArtistsLoading=MutableStateFlow(false)
    val onboardingArtistsLoading: StateFlow<Boolean> = _onboardingArtistsLoading
    private val _onboardingArtistsError=MutableStateFlow<String?>(null)
    val onboardingArtistsError: StateFlow<String?> = _onboardingArtistsError
    fun discoverOnboardingArtists(languages: Set<String>,query: String="") {
        onboardingArtistsJob?.cancel();val generation=++artistGeneration
        _onboardingArtists.value=if(query.isBlank())com.mousike.app.ui.personalization.ArtistSuggestions.rank(MusicCatalog.ARTISTS,MusicCatalog.TRACKS,tasteActivity,preferences.value.copy(languages=languages)) else MusicCatalog.ARTISTS.filter {it.name.contains(query,true)}.take(48)
        _onboardingArtistsError.value=null
        onboardingArtistsJob=viewModelScope.launch {
            _onboardingArtistsLoading.value=true
            try {
                kotlinx.coroutines.delay(250)
                val seeds=if(query.isNotBlank())listOf(query.take(100)) else com.mousike.app.ui.personalization.ArtistSuggestions.seeds(languages,preferences.value.artists,10)
                val found=linkedMapOf<String,Artist>()
                if(query.isBlank())_onboardingArtists.value.forEach {found[it.id]=it}
                for(batch in seeds.chunked(2)) {
                    val results=kotlinx.coroutines.coroutineScope {batch.map {seed->async {
                        try {repository.remoteSearch(seed).artists} catch(e: kotlinx.coroutines.CancellationException){throw e} catch(_: Exception){emptyList()}
                    }}.let {jobs->jobs.map {it.await()}}}
                    results.flatten().filter {it.name.isNotBlank()}.forEach {found[it.id]=it}
                    _onboardingArtists.value=if(query.isBlank())com.mousike.app.ui.personalization.ArtistSuggestions.rank(found.values.toList(),MusicCatalog.TRACKS,tasteActivity,preferences.value.copy(languages=languages)) else found.values.distinctBy {it.name.lowercase()}.sortedByDescending {it.avatarUrl.isNotBlank()}.take(60)
                }
                if(found.isEmpty())_onboardingArtistsError.value="Artist photos need a connection. Retry, search for an artist, or continue and choose later."
            } finally {if(generation==artistGeneration)_onboardingArtistsLoading.value=false}
        }
    }
    fun checkGemini() { viewModelScope.launch { runCatching { repository.gemini.validate() }.onSuccess { showSnackbar(it) }.onFailure { showSnackbar(it.message ?: "Gemini check failed") } } }
    fun saveGeminiKey(key: String) { runCatching { settings.saveApiKey(key) }.onSuccess { showSnackbar(if(key.isBlank()) "API key removed" else "API key saved securely") }.onFailure { showSnackbar("Could not save API key on this device") } }
    fun updateExtractor() { viewModelScope.launch { showSnackbar("Updating the bundled extractor…"); runCatching { InnerTubeExtractor.get(getApplication()).update() }.onSuccess { showSnackbar(it) }.onFailure { showSnackbar("Extractor update failed. Check the connection and retry.") } } }
    fun download(track: Track) { DownloadStore.get(getApplication()).enqueue(track); showSnackbar("Download queued${if(preferences.value.wifiDownloads) " • waiting for Wi-Fi if needed" else ""}") }
    fun cancelDownload(track: Track) { DownloadStore.get(getApplication()).cancel(track) }
    fun deleteDownload(track: Track) { DownloadStore.get(getApplication()).delete(track) }
    fun playNextInQueue(track: Track) { sessionQueue.markManual(track);playerEngine.playNextInQueue(track);showSnackbar("Playing next: ${track.title}") }
    fun moveQueue(from: Int,to: Int) {sessionQueue.acceptManualOrder();playerEngine.moveQueue(from,to)}
    fun loadSoundCheck() { viewModelScope.launch { withContext(Dispatchers.IO) { com.mousike.app.data.catalog.DemoCatalog.install(getApplication()) }; _catalogVersion.value++;loadRecommendations();showSnackbar("Offline sound check added. These are original bundled demo tracks.") } }

    fun togglePlayPause() = playerEngine.togglePlayPause()
    fun playNext() {if(!sessionQueue.next())playerEngine.playNext()}
    fun playPrevious() = playerEngine.playPrevious()
    fun seekTo(positionMs: Long) = playerEngine.seekTo(positionMs)
    fun toggleShuffle() = playerEngine.toggleShuffle()
    fun cycleRepeatMode() = playerEngine.cycleRepeatMode()
    fun setPlaybackSpeed(speed: Float) = playerEngine.setPlaybackSpeed(speed)
    fun setSleepTimer(minutes: Int?) = playerEngine.setSleepTimer(minutes)
    fun setEqualizerPreset(preset: String) = playerEngine.setEqualizerPreset(preset)
    fun setCustomEqualizer(bass: Float, vocal: Float, treble: Float) = playerEngine.setCustomEqualizer(bass, vocal, treble)
    fun addToQueue(track: Track) {
        sessionQueue.markManual(track);playerEngine.addToQueue(track)
        showSnackbar("Added to queue: ${track.title}")
    }
    fun removeFromQueue(index: Int) {playbackState.value.queue.getOrNull(index)?.let {sessionQueue.removeManual(it.id)};playerEngine.removeFromQueue(index)}
    fun clearQueue() {sessionQueue.userClearedQueue();playerEngine.clearQueue()}

    // Like / Save
    fun toggleLikeTrack(trackId: String) {
        viewModelScope.launch {
            val isNowLiked = repository.toggleLike(trackId)
            sessionQueue.refresh(force=true)
            showSnackbar(if (isNowLiked) "Added to Liked Songs" else "Removed from Liked Songs")
            loadRecommendations()
        }
    }

    fun toggleSaveAlbum(albumId: String) {
        viewModelScope.launch {
            val saved = repository.toggleSaveAlbum(albumId)
            showSnackbar(if (saved) "Saved album to library" else "Removed album from library")
        }
    }

    fun toggleSaveArtist(artistId: String) {
        viewModelScope.launch {
            val saved = repository.toggleSaveArtist(artistId)
            showSnackbar(if (saved) "Following artist" else "Unfollowed artist")
            refreshPersonalPresentation()
        }
    }

    // Playlists
    fun createPlaylist(name: String, description: String = "") {
        viewModelScope.launch {
            val id = repository.createPlaylist(name, description)
            _showCreatePlaylistDialog.value = false
            showSnackbar("Playlist created: $name")
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            showSnackbar("Playlist deleted")
            if (_selectedPlaylist.value?.id == playlistId) {
                navigateBack()
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(playlistId, trackId)
            _trackToAddToPlaylist.value = null
            showSnackbar("Added to playlist")
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            repository.removeTrackFromPlaylist(playlistId, trackId)
            showSnackbar("Removed from playlist")
        }
    }

    // Search Actions
    fun updateSearchQuery(query: String) {
        if(_searchQuery.value!=query){_isSearching.value=query.isNotBlank();_searchError.value=null;_searchResults.value=SearchResults()}
        _searchQuery.value = query
    }

    fun commitSearchQuery(query: String) {
        if (query.isNotBlank()) {
            viewModelScope.launch {
                repository.recordSearchQuery(query)
            }
        }
    }

    fun removeSearchHistory(query: String) {
        viewModelScope.launch {
            repository.removeSearchQuery(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { repository.clearSearchHistory() }
    }

    fun clearListeningHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            sessionQueue.clearListeningMemory()
            loadRecommendations()
            showSnackbar("Listening history cleared")
        }
    }

    // Recommendation Feedback
    fun sendFeedback(track: Track, feedbackType: String) {
        viewModelScope.launch {
            repository.recordFeedback(track, feedbackType)
            sessionQueue.refresh(force=true)
            _showFeedbackDialogForTrack.value = null
            val msg = when (feedbackType) {
                "MORE_LIKE_THIS" -> "We'll recommend more music like this"
                "NOT_INTERESTED" -> "Got it, won't show this as often"
                "LESS_OF_ARTIST" -> "We will play less ${track.artist}"
                "DONT_RECOMMEND" -> "Removed from recommendations"
                else -> "Feedback saved"
            }
            showSnackbar(msg)
            loadRecommendations()
        }
    }

    // Dialog Toggles
    fun setCreatePlaylistDialogVisible(visible: Boolean) {
        _showCreatePlaylistDialog.value = visible
    }

    fun setTrackToAddToPlaylist(track: Track?) {
        _trackToAddToPlaylist.value = track
    }

    fun setSleepTimerDialogVisible(visible: Boolean) {
        _showSleepTimerDialog.value = visible
    }

    fun setEqualizerDialogVisible(visible: Boolean) {
        _showEqualizerDialog.value = visible
    }

    fun setFeedbackDialogForTrack(track: Track?) {
        _showFeedbackDialogForTrack.value = track
    }

    // Data Export / Import
    fun exportData(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportLibraryToJson()
            onResult(json)
            showSnackbar("Library exported successfully")
        }
    }

    fun importData(jsonString: String) {
        viewModelScope.launch {
            val success = repository.importLibraryFromJson(jsonString)
            if (success) {
                showSnackbar("Library imported successfully!")
                loadRecommendations()
            } else {
                showSnackbar("Failed to import JSON file")
            }
        }
    }

    fun clearTemporaryCache() {viewModelScope.launch {
        com.mousike.app.performance.CacheMaintenance.clearTemporary(getApplication())
        showSnackbar("Temporary cache cleared. Your downloads and library are kept.")
    }}

    fun clearAllData() {
        viewModelScope.launch {
            sessionQueue.reset()
            repository.clearAllUserData()
            playerEngine.reset()
            DownloadStore.get(getApplication()).clear()
            settings.clear()
            withContext(Dispatchers.IO) {com.mousike.app.data.catalog.CatalogCache.clear(getApplication())}
            MusicCatalog.clear()
            showSnackbar("All your saved local data has been cleared")
            loadRecommendations()
        }
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }
    override fun onCleared() {playerEngine.onTrackStarted=null;playerEngine.onTrackCompleted=null;playerEngine.onTrackSkipped=null;qualityBinding.close();super.onCleared()}
}
