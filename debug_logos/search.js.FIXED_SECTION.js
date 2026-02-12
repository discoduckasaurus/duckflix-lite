/**
 * GET /api/search/tmdb/:id
 * Get TMDB content details
 *
 * FIXED VERSION - Now includes logoPath, originalLanguage, and spokenLanguages
 */
router.get('/tmdb/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const { type } = req.query;

    if (!id) {
      return res.status(400).json({ error: 'ID parameter required' });
    }

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Check cache first
    const contentType = type === 'tv' ? 'tv' : 'movie';
    const cacheKey = `details_${contentType}_${id}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    // Add small delay to avoid rate limits
    await new Promise(resolve => setTimeout(resolve, 250));

    const url = `https://api.themoviedb.org/3/${contentType}/${id}`;

    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey,
        // FIXED: Added 'images' to get logos
        append_to_response: type === 'tv'
          ? 'credits,videos,external_ids,seasons,images'
          : 'credits,videos,external_ids,images'
      }
    });

    const data = response.data;

    // FIXED: Extract English logo (transparent PNG for loading screen overlays)
    let logoPath = null;
    if (data.images && data.images.logos && data.images.logos.length > 0) {
      // Prefer English logo
      const englishLogo = data.images.logos.find(logo => logo.iso_639_1 === 'en');
      logoPath = (englishLogo || data.images.logos[0]).file_path;
    }

    const result = {
      id: data.id,
      title: data.title || data.name,
      overview: data.overview,
      posterPath: data.poster_path,
      backdropPath: data.backdrop_path,
      logoPath: logoPath,  // FIXED: Added logoPath
      releaseDate: data.release_date || data.first_air_date,
      voteAverage: data.vote_average,
      runtime: data.runtime || (data.episode_run_time && data.episode_run_time[0]),
      genres: data.genres,

      // FIXED: Added for smart audio track selection
      originalLanguage: data.original_language,  // e.g., "en", "ja", "es"
      spokenLanguages: data.spoken_languages?.map(lang => ({
        iso6391: lang.iso_639_1,
        name: lang.name
      })),

      // FIXED: Added actor ID for navigation to filmography
      cast: data.credits?.cast?.slice(0, 10).map(c => ({
        name: c.name,
        character: c.character,
        profilePath: c.profile_path,
        id: c.id
      })) || [],

      externalIds: data.external_ids,

      // TV show specific fields
      ...(type === 'tv' && {
        numberOfSeasons: data.number_of_seasons,
        seasons: data.seasons?.map(s => ({
          id: s.id,
          name: s.name,
          seasonNumber: s.season_number,
          posterPath: s.poster_path,
          episodeCount: s.episode_count,
          overview: s.overview
        }))
      })
    };

    // Cache the result
    cache.set(cacheKey, {
      data: result,
      timestamp: Date.now()
    });

    res.json(result);
  } catch (error) {
    logger.error('TMDB detail error:', error);
    res.status(500).json({ error: 'Failed to fetch details' });
  }
});
