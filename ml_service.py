from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import numpy as np
import pandas as pd
import time
import os
import yfinance as yf
from sklearn.preprocessing import MinMaxScaler
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, Dropout, Conv1D, MaxPooling1D, Flatten
from tensorflow.keras.callbacks import EarlyStopping
import warnings
warnings.filterwarnings("ignore")

app = FastAPI(title="Stock ML Service - Indian Market")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8080", "http://localhost:4200"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
MAX_TRAINING_ROWS = 250  # ~1 trading year; caps LSTM input regardless of source
# ─── CONFIGURATION ───
DATASET_PATH = "./datasets/indian_stock_market.csv"
COMPREHENSIVE_PATH = "./datasets/indian_stock_market_comprehensive_2000_2026.csv"
MODELS_DIR = "./models"
os.makedirs(MODELS_DIR, exist_ok=True)
os.makedirs("./datasets", exist_ok=True)

# ─── LOAD KAGGLE DATASET AT STARTUP ───
kaggle_df = None
kaggle_ticker_col = None
kaggle_date_col = None

def load_kaggle_dataset():
    global kaggle_df, kaggle_ticker_col, kaggle_date_col
    if not os.path.exists(DATASET_PATH):
        print("[DATA] Kaggle dataset not found. Will use yfinance/Java arrays.")
        return

    try:
        df = pd.read_csv(DATASET_PATH)
        df.columns = [c.strip().lower().replace(" ", "_") for c in df.columns]

        for col in ['symbol', 'ticker', 'stock', 'scrip', 'name']:
            if col in df.columns:
                kaggle_ticker_col = col
                break

        for col in ['date', 'trading_date', 'timestamp', 'datetime']:
            if col in df.columns:
                kaggle_date_col = col
                df[col] = pd.to_datetime(df[col])
                break

        if kaggle_ticker_col is None:
            print("[DATA] No ticker column found in Kaggle dataset.")
            return

        kaggle_df = df
        print(f"[DATA] Loaded Kaggle dataset: {len(kaggle_df)} rows, ticker_col={kaggle_ticker_col}")
    except Exception as e:
        print(f"[DATA] Failed to load Kaggle: {e}")

load_kaggle_dataset()

# ─── GENERATE COMPREHENSIVE CSV (2000-2026) IF NOT EXISTS ───
def generate_comprehensive_csv():
    """Generate a comprehensive Indian stock market CSV from 2000-2026 using yfinance."""
    if os.path.exists(COMPREHENSIVE_PATH):
        print(f"[DATA] Comprehensive CSV already exists: {COMPREHENSIVE_PATH}")
        return

    print("[DATA] Generating comprehensive Indian stock market CSV (2000-2026)...")

    # Major Indian indices and blue chips
    indian_tickers = [
        "RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "INFY.NS", "ICICIBANK.NS",
        "HINDUNILVR.NS", "SBIN.NS", "BHARTIARTL.NS", "ITC.NS", "KOTAKBANK.NS",
        "LT.NS", "AXISBANK.NS", "BAJFINANCE.NS", "ASIANPAINT.NS", "MARUTI.NS",
        # Tata Motors demerged Oct 2025: the old TATAMOTORS.NS symbol now
        # belongs to the passenger-vehicle business, renamed TMPV.NS. The
        # commercial-vehicle spinoff took back the TATAMOTORS name under a
        # different listing — update this if you specifically want that one.
        "TMPV.NS", "SUNPHARMA.NS", "WIPRO.NS", "NESTLEIND.NS", "ULTRACEMCO.NS",
        "^NSEI", "^BSESN"  # Nifty 50 and Sensex indices
    ]

    all_data = []
    for ticker in indian_tickers:
        try:
            print(f"[DATA] Fetching {ticker}...")
            # auto_adjust=False so yfinance still returns a separate
            # 'Adj Close' column — newer yfinance defaults to auto_adjust=True,
            # which folds the adjustment into 'Close' and drops 'Adj Close'
            # entirely, which is exactly what was causing
            # "['adj_close'] not in index" for every ticker below.
            df = yf.download(ticker, start="2000-01-01", end="2026-08-29",
                              interval="1d", progress=False, auto_adjust=False)
            if df.empty or len(df) < 100:
                continue

            # Flatten multi-index columns from yfinance
            if isinstance(df.columns, pd.MultiIndex):
                df.columns = [c[0] for c in df.columns]

            df = df.reset_index()
            df['ticker'] = ticker.replace(".NS", "")
            df['exchange'] = 'NSE' if '.NS' in ticker else 'INDEX'

            # Ensure standard column names
            column_mapping = {
                'Date': 'date', 'Open': 'open', 'High': 'high',
                'Low': 'low', 'Close': 'close', 'Volume': 'volume',
                'Adj Close': 'adj_close'
            }
            df = df.rename(columns={k: v for k, v in column_mapping.items() if k in df.columns})

            # Belt-and-suspenders: some symbols (e.g. index tickers like
            # ^NSEI) genuinely have no adjustment data even with
            # auto_adjust=False — fall back to unadjusted close rather than
            # dropping the ticker entirely.
            if 'adj_close' not in df.columns:
                df['adj_close'] = df['close']

            all_data.append(df[['date', 'ticker', 'exchange', 'open', 'high', 'low', 'close', 'volume', 'adj_close']])
        except Exception as e:
            print(f"[DATA] Failed to fetch {ticker}: {e}")

    if all_data:
        comprehensive = pd.concat(all_data, ignore_index=True)
        comprehensive = comprehensive.sort_values(['ticker', 'date'])
        comprehensive.to_csv(COMPREHENSIVE_PATH, index=False)
        print(f"[DATA] Comprehensive CSV saved: {len(comprehensive)} rows, {comprehensive['ticker'].nunique()} tickers")
    else:
        print("[DATA] Failed to generate comprehensive CSV")

# Run generation in background (non-blocking)
import threading
threading.Thread(target=generate_comprehensive_csv, daemon=True).start()

# ─── DATA MODELS ───
class ForecastRequest(BaseModel):
    ticker: str
    days_to_predict: int = 1
    historical_closes: list[float] | None = None

class PatternRequest(BaseModel):
    ticker: str
    closes: list[float] | None = None
    lows: list[float] | None = None

class ForecastResponse(BaseModel):
    ticker: str
    last_close: float
    predictions: list[float]
    days_to_predict: int
    train_mae: float
    confidence: float
    data_source: str

class PatternResponse(BaseModel):
    ticker: str
    pattern: str
    confidence: float
    trend: str
    details: dict

# ─── DATA LOADING ───
def get_ticker_data(ticker: str, java_closes: list[float] | None = None):
    """Priority: Kaggle > Comprehensive CSV > Java arrays > yfinance live"""
    ticker = ticker.strip().upper()

    # 1. Try Kaggle dataset first
    if kaggle_df is not None and kaggle_ticker_col:
        df = kaggle_df[kaggle_df[kaggle_ticker_col].str.upper() == ticker].copy()
        if len(df) >= 30:
            if kaggle_date_col:
                df = df.sort_values(kaggle_date_col)

            col_map = {}
            for target in ['open', 'high', 'low', 'close', 'volume']:
                for src in df.columns:
                    if target in src and target not in col_map:
                        col_map[target] = src

            clean = pd.DataFrame()
            for target, src in col_map.items():
                clean[target] = pd.to_numeric(df[src].astype(str).str.replace(',', ''), errors='coerce')

            clean = clean.dropna().reset_index(drop=True)
            if len(clean) >= 30:
                print(f"[DATA] Kaggle hit for {ticker}: {len(clean)} rows")
                return clean, "kaggle"

    # 2. Try comprehensive CSV (2000-2026)
    if os.path.exists(COMPREHENSIVE_PATH):
        try:
            comp_df = pd.read_csv(COMPREHENSIVE_PATH)
            comp_df['ticker'] = comp_df['ticker'].str.upper()
            df = comp_df[comp_df['ticker'] == ticker].copy()
            if len(df) >= 30:
                df['date'] = pd.to_datetime(df['date'])
                df = df.sort_values('date')
                for col in ['open', 'high', 'low', 'close', 'volume']:
                    df[col] = pd.to_numeric(df[col], errors='coerce')
                df = df.dropna()
                print(f"[DATA] Comprehensive CSV hit for {ticker}: {len(df)} rows")
                return df[['open', 'high', 'low', 'close', 'volume']], "comprehensive_csv"
        except Exception as e:
            print(f"[DATA] Comprehensive CSV read failed: {e}")

    # 3. Try Java-provided arrays
    if java_closes and len(java_closes) >= 30:
        print(f"[DATA] Using Java arrays for {ticker}: {len(java_closes)} rows")
        return pd.DataFrame({
            'close': java_closes,
            'low': java_closes,
            'high': java_closes,
            'open': java_closes,
            'volume': [0] * len(java_closes)
        }), "java_arrays"

    # 4. Fallback to yfinance
    print(f"[DATA] Fallback to yfinance for {ticker}")
    try:
        yf_ticker = ticker + ".NS" if not ticker.endswith(".NS") else ticker
        df_yf = yf.download(yf_ticker, period="5y", interval="1d", progress=False)
        if df_yf.empty or len(df_yf) < 30:
            df_yf = yf.download(ticker, period="5y", interval="1d", progress=False)

        if not df_yf.empty and len(df_yf) >= 30:
            if isinstance(df_yf.columns, pd.MultiIndex):
                df_yf.columns = [c[0] for c in df_yf.columns]
            for col in ['open', 'high', 'low', 'close', 'volume']:
                if col not in df_yf.columns:
                    df_yf[col] = df_yf['close'] if col != 'volume' else 0
            return df_yf[['open', 'high', 'low', 'close', 'volume']].dropna(), "yfinance_live"
    except Exception as e:
        print(f"[DATA] yfinance failed: {e}")

    raise HTTPException(status_code=404, detail=f"No data available for {ticker}")

# ─── LSTM MODEL ───
def build_lstm(seq_len: int):
    model = Sequential([
        LSTM(64, return_sequences=True, input_shape=(seq_len, 1)),
        Dropout(0.2),
        LSTM(32, return_sequences=False),
        Dropout(0.2),
        Dense(16, activation='relu'),
        Dense(1)
    ])
    model.compile(optimizer='adam', loss='mean_squared_error', metrics=['mae'])
    return model

# ─── CNN MODEL (for pattern classification) ───
def build_cnn(seq_len: int, n_classes: int):
    model = Sequential([
        Conv1D(32, kernel_size=3, activation='relu', input_shape=(seq_len, 1)),
        MaxPooling1D(pool_size=2),
        Conv1D(64, kernel_size=3, activation='relu'),
        MaxPooling1D(pool_size=2),
        Flatten(),
        Dense(32, activation='relu'),
        Dropout(0.3),
        Dense(n_classes, activation='softmax')
    ])
    model.compile(optimizer='adam', loss='sparse_categorical_crossentropy', metrics=['accuracy'])
    return model

# ─── TRAINING & PREDICTION (forecasting) ───
def train_and_predict(df: pd.DataFrame, ticker: str, days: int = 1):
    closes = df['close'].values.reshape(-1, 1)
    scaler = MinMaxScaler(feature_range=(0, 1))
    scaled = scaler.fit_transform(closes)

    seq_len = min(60, len(scaled) - 1)
    if seq_len < 10:
        raise HTTPException(status_code=400, detail="Need at least 10 data points")

    print(f"[TRAIN] {ticker}: input_rows={len(scaled)} seq_len={seq_len}")

    X, y = [], []
    for i in range(seq_len, len(scaled)):
        X.append(scaled[i - seq_len:i])
        y.append(scaled[i])
    X, y = np.array(X), np.array(y)

    split = int(0.85 * len(X))
    if split < 1:
        split = len(X) - 1 if len(X) > 1 else 1

    X_train, X_val = X[:split], X[split:]
    y_train, y_val = y[:split], y[split:]

    # Guard against an empty validation split on small datasets
    if len(X_val) == 0:
        X_val, y_val = X_train[-1:], y_train[-1:]

    model = build_lstm(seq_len)

    early_stop = EarlyStopping(
        monitor='val_loss',
        patience=8,
        restore_best_weights=True
    )

    model_path = os.path.join(MODELS_DIR, f"{ticker}_lstm.keras")

    history = model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=100,
        batch_size=16,
        callbacks=[early_stop],
        verbose=0
    )

    val_mae_scaled = float(min(history.history.get('val_mae', [0.0])))
    # Convert the scaled MAE back into price units for a human-readable metric
    price_range = float(scaler.data_max_[0] - scaler.data_min_[0])
    train_mae = val_mae_scaled * price_range

    try:
        model.save(model_path)
    except Exception as e:
        print(f"[MODEL] Failed to save model for {ticker}: {e}")

    # Iteratively forecast `days` steps ahead, feeding each prediction back in
    last_window = scaled[-seq_len:].reshape(1, seq_len, 1)
    predictions_scaled = []
    current_window = last_window.copy()

    for _ in range(days):
        next_scaled = model.predict(current_window, verbose=0)[0][0]
        predictions_scaled.append(next_scaled)
        current_window = np.append(current_window[:, 1:, :], [[[next_scaled]]], axis=1)

    predictions = scaler.inverse_transform(
        np.array(predictions_scaled).reshape(-1, 1)
    ).flatten().tolist()

    last_close = float(closes[-1][0])
    # Rough confidence heuristic: smaller relative validation error -> higher confidence
    relative_error = train_mae / last_close if last_close else 1.0
    confidence = max(0.0, min(1.0, 1.0 - relative_error))

    return {
        "last_close": last_close,
        "predictions": predictions,
        "train_mae": round(train_mae, 4),
        "confidence": round(confidence, 4)
    }

# ─── RULE-BASED PATTERN DETECTION ───
def detect_pattern(df: pd.DataFrame):
    """
    Lightweight, explainable technical-pattern detector built on moving
    averages, RSI and local extrema. This runs instantly (no training) and
    is meant as a first pass — swap in the CNN classifier above once you
    have labeled pattern data to train it on.
    """
    closes = df['close'].astype(float).reset_index(drop=True)
    lows = df['low'].astype(float).reset_index(drop=True) if 'low' in df.columns else closes

    if len(closes) < 20:
        raise HTTPException(status_code=400, detail="Need at least 20 data points for pattern detection")

    sma_short = closes.rolling(10).mean()
    sma_long = closes.rolling(20).mean()

    # RSI (14-period)
    delta = closes.diff()
    gain = delta.clip(lower=0).rolling(14).mean()
    loss = (-delta.clip(upper=0)).rolling(14).mean()
    rs = gain / loss.replace(0, np.nan)
    rsi = (100 - (100 / (1 + rs))).fillna(50)

    last_close = closes.iloc[-1]
    last_sma_short = sma_short.iloc[-1]
    last_sma_long = sma_long.iloc[-1]
    last_rsi = rsi.iloc[-1]

    # Trend from moving-average crossover
    if last_sma_short > last_sma_long:
        trend = "bullish"
    elif last_sma_short < last_sma_long:
        trend = "bearish"
    else:
        trend = "neutral"

    # Local extrema over the recent window to flag double top/bottom
    window = closes.tail(30).values
    pattern = "no_clear_pattern"
    confidence = 0.5

    peak_idx = np.argmax(window)
    trough_idx = np.argmin(window)

    if last_rsi >= 70:
        pattern = "overbought_possible_reversal"
        confidence = min(1.0, (last_rsi - 70) / 30 + 0.5)
    elif last_rsi <= 30:
        pattern = "oversold_possible_reversal"
        confidence = min(1.0, (30 - last_rsi) / 30 + 0.5)
    elif 0 < peak_idx < len(window) - 1 and window[peak_idx] == max(window[:peak_idx + 1]):
        recent_peaks = [v for v in window if v > window.mean() + window.std() * 0.5]
        if len(recent_peaks) >= 2 and abs(recent_peaks[0] - recent_peaks[-1]) / window.mean() < 0.03:
            pattern = "double_top"
            confidence = 0.65
    elif 0 < trough_idx < len(window) - 1:
        recent_troughs = [v for v in window if v < window.mean() - window.std() * 0.5]
        if len(recent_troughs) >= 2 and abs(recent_troughs[0] - recent_troughs[-1]) / window.mean() < 0.03:
            pattern = "double_bottom"
            confidence = 0.65

    return {
        "pattern": pattern,
        "confidence": round(float(confidence), 4),
        "trend": trend,
        "details": {
            "last_close": round(float(last_close), 2),
            "sma_10": round(float(last_sma_short), 2) if not np.isnan(last_sma_short) else None,
            "sma_20": round(float(last_sma_long), 2) if not np.isnan(last_sma_long) else None,
            "rsi_14": round(float(last_rsi), 2)
        }
    }

# ─── ENDPOINTS ───
@app.get("/health")
def health():
    return {
        "status": "ok",
        "kaggle_loaded": kaggle_df is not None,
        "comprehensive_csv_exists": os.path.exists(COMPREHENSIVE_PATH)
    }
@app.post("/forecast", response_model=ForecastResponse)
def forecast(req: ForecastRequest):
    if req.days_to_predict < 1 or req.days_to_predict > 30:
        raise HTTPException(status_code=400, detail="days_to_predict must be between 1 and 30")

    t0 = time.time()
    df, source = get_ticker_data(req.ticker, java_closes=req.historical_closes)
    t1 = time.time()

    original_rows = len(df)
    if len(df) > MAX_TRAINING_ROWS:
        df = df.tail(MAX_TRAINING_ROWS).reset_index(drop=True)

    print(f"[TIMING] {req.ticker}: data_load={t1-t0:.2f}s | "
          f"source={source} | rows={original_rows} -> capped={len(df)}")

    t2 = time.time()
    result = train_and_predict(df, req.ticker.strip().upper(), days=req.days_to_predict)
    t3 = time.time()

    print(f"[TIMING] {req.ticker}: train_predict={t3-t2:.2f}s | total={t3-t0:.2f}s")

    return ForecastResponse(
        ticker=req.ticker.strip().upper(),
        last_close=result["last_close"],
        predictions=result["predictions"],
        days_to_predict=req.days_to_predict,
        train_mae=result["train_mae"],
        confidence=result["confidence"],
        data_source=source
    )
@app.post("/detect-pattern", response_model=PatternResponse)
def pattern_endpoint(req: PatternRequest):
    if req.closes and len(req.closes) >= 20:
        df = pd.DataFrame({
            'close': req.closes,
            'low': req.lows if req.lows and len(req.lows) == len(req.closes) else req.closes
        })
        source_note = "request_arrays"
    else:
        df, source_note = get_ticker_data(req.ticker)

    result = detect_pattern(df)

    return PatternResponse(
        ticker=req.ticker.strip().upper(),
        pattern=result["pattern"],
        confidence=result["confidence"],
        trend=result["trend"],
        details=result["details"]
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)