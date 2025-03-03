package com.example.midterm

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.dataStore by preferencesDataStore(name = "weather_prefs")

interface WeatherApi {
    @GET("forecast")
    suspend fun getWeatherForecast(
        @Query("q") cityName: String,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): retrofit2.Response<WeatherForecastResponse>
}

data class WeatherForecastResponse(val list: List<WeatherItem>)
data class WeatherItem(val main: Main, val weather: List<Weather>, val dt_txt: String)
data class Main(val temp: Float)
data class Weather(val description: String, val icon: String)

data class CityWeather(
    val cityName: String,
    val temperature: String,
    val description: String,
    val date: String,
    val iconUrl: String,
    val forecast: List<ForecastItem>
)

data class ForecastItem(val date: String, val temp: Float)

class WeatherViewModel(private val context: Context) : ViewModel() {
    private val _weatherList = MutableStateFlow<List<CityWeather>>(emptyList())
    val weatherList: StateFlow<List<CityWeather>> = _weatherList
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    private val api = Retrofit.Builder()
        .baseUrl("https://api.openweathermap.org/data/2.5/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    var isCelsius = MutableStateFlow(true)

    init {
        viewModelScope.launch { loadSavedCities() }
    }

    fun fetchWeatherFromApi(cityName: String, apiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val units = if (isCelsius.value) "metric" else "imperial"
                val response = api.create(WeatherApi::class.java).getWeatherForecast(cityName, apiKey, units)
                if (response.isSuccessful) {
                    response.body()?.let { apiResponse ->
                        if (apiResponse.list.isNotEmpty()) {
                            val weather = CityWeather(
                                cityName = cityName,
                                temperature = "${apiResponse.list[0].main.temp} ${if (isCelsius.value) "°C" else "°F"}",
                                description = apiResponse.list[0].weather.firstOrNull()?.description ?: "N/A",
                                date = getCurrentDate(),
                                iconUrl = "https://openweathermap.org/img/wn/${apiResponse.list[0].weather.firstOrNull()?.icon}.png",
                                forecast = parseForecast(apiResponse.list)
                            )
                            _weatherList.value = _weatherList.value + weather
                            saveCities()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun switchUnits() {
        isCelsius.value = !isCelsius.value
        _weatherList.value = _weatherList.value.map { cityWeather ->
            val currentTemp = cityWeather.temperature.split(" ")[0].toFloat()
            val newTemp = if (isCelsius.value) (currentTemp - 32) * 5 / 9 else (currentTemp * 9 / 5) + 32
            cityWeather.copy(
                temperature = "%.1f ${if (isCelsius.value) "°C" else "°F"}".format(newTemp)
            )
        }
        saveCities()
    }

    fun deleteCityWeather(cityName: String) {
        _weatherList.value = _weatherList.value.filter { it.cityName != cityName }
        saveCities()
    }

    private fun getCurrentDate(): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun parseForecast(weatherItems: List<WeatherItem>): List<ForecastItem> {
        return weatherItems
            .groupBy { it.dt_txt.split(" ")[0] }
            .map { (date, items) ->
                val averageTemp = items.map { it.main.temp }.average().toFloat()
                ForecastItem(date = date, temp = averageTemp)
            }
    }

    private fun saveCities() {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[stringPreferencesKey("cities")] =
                    _weatherList.value.joinToString(";") { "${it.cityName}|${it.temperature}|${it.description}|${it.date}|${it.iconUrl}" }
            }
        }
    }

    private suspend fun loadSavedCities() {
        val savedCities = context.dataStore.data
            .map { prefs ->
                prefs[stringPreferencesKey("cities")]?.split(";")?.mapNotNull { cityData ->
                    val parts = cityData.split("|")
                    if (parts.size == 5) {
                        CityWeather(
                            cityName = parts[0],
                            temperature = parts[1],
                            description = parts[2],
                            date = parts[3],
                            iconUrl = parts[4],
                            forecast = emptyList()
                        )
                    } else null
                } ?: emptyList()
            }
            .first()
        _weatherList.value = savedCities
    }
}

class WeatherViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WeatherViewModel::class.java)) {
            return WeatherViewModel(context) as T
        }
        throw IllegalArgumentException("unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var broadcastReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = ViewModelProvider(this, WeatherViewModelFactory(this)).get(WeatherViewModel::class.java)

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) {
                    when (intent.action) {
                        "com.example.midterm.ACTION_UPDATE_WEATHER" -> {
                            val cityName = intent.getStringExtra("cityName")
                            cityName?.let {
                                val apiKey = "b42e8a422f7eecca5d506a9b6c86d98a"
                                viewModel.fetchWeatherFromApi(it, apiKey)
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.example.midterm.ACTION_UPDATE_WEATHER")
        }

        registerReceiver(broadcastReceiver, filter)

        setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "weather_app") {
                composable("weather_app") {
                    WeatherApp(viewModel = viewModel, navController = navController)
                }
                composable("city_details/{cityName}") { backStackEntry ->
                    val cityName = backStackEntry.arguments?.getString("cityName") ?: ""
                    CityDetailsPage(viewModel, cityName)
                }
                composable("settings") {
                    SettingsPage(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherApp(viewModel: WeatherViewModel, navController: NavHostController) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val weatherList by viewModel.weatherList.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weather App") },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Image(
                            painter = painterResource(id = R.drawable.settings),
                            contentDescription = "Settings Icon"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                label = { Text("Search City") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            Button(
                onClick = {
                    val apiKey = "b42e8a422f7eecca5d506a9b6c86d98a"
                    viewModel.fetchWeatherFromApi(searchQuery, apiKey)
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Search")
            }
            if (weatherList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "No cities added.", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(weatherList) { weather ->
                        WeatherRow(weather = weather, navController = navController, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherRow(weather: CityWeather, navController: NavHostController, viewModel: WeatherViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable { navController.navigate("city_details/${weather.cityName}") },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberImagePainter(weather.iconUrl),
            contentDescription = "Weather Icon",
            modifier = Modifier.size(50.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "City: ${weather.cityName}", style = MaterialTheme.typography.titleMedium)
            Text(text = "Temperature: ${weather.temperature}", style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { viewModel.deleteCityWeather(weather.cityName) }) {
            Text("Delete", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun CityDetailsPage(viewModel: WeatherViewModel, cityName: String) {
    val weather = viewModel.weatherList.value.firstOrNull { it.cityName == cityName }
    weather?.let {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = rememberImagePainter(it.iconUrl),
                contentDescription = "Weather Icon",
                modifier = Modifier.size(100.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("City: ${it.cityName}", style = MaterialTheme.typography.titleLarge)
            Text("Temperature: ${it.temperature}", style = MaterialTheme.typography.titleMedium)
            Text("Description: ${it.description}", style = MaterialTheme.typography.bodyMedium)
            Text("Date: ${it.date}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Daily Temperatures:", style = MaterialTheme.typography.bodyLarge)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(it.forecast) { forecast ->
                    Text(
                        "Date: ${forecast.date}, Avg Temp: ${forecast.temp}°",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsPage(viewModel: WeatherViewModel) {
    val isCelsius by viewModel.isCelsius.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { viewModel.switchUnits() }) {
            Text("Switch to ${if (isCelsius) "Fahrenheit" else "Celsius"}")
        }
    }
}
