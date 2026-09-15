"""Fraud detection feature engineering.

Bu modül, eğitim (notebook) ve canlı tahmin (FastAPI ML servisi) tarafından
ortak kullanılacak feature engineering fonksiyonlarını içerir.

Tüm fonksiyonlar leakage-safe'tir: bir işlem için hesaplanan özellikler,
yalnızca o işlemden ÖNCEki geçmişi kullanır.
"""

import pandas as pd


def add_uid(df: pd.DataFrame) -> pd.DataFrame:
    """IEEE-CIS veri setinde açık bir user_id yoktur.

    card1 + card2 + card3 + card5 + addr1 + D1n kombinasyonu, aynı kullanıcıyı
    güçlü ihtimalle işaret eden bir pseudo-kimlik (uid) olarak kullanılır. Bu
    kesin bir kullanıcı ID'si değil, bir yaklaşıklıktır.

    D1 (kartın ilk işleminden bu yana geçen gün sayısı) zamanla arttığı için,
    ham haliyle kullanılırsa aynı kullanıcı farklı işlemlerinde farklı D1n
    değerleri üretip uid'i gereksiz yere böler. D1n = D1 - transaction_day
    normalizasyonu bu değeri zamandan bağımsız, sabit bir değere çevirir.

    Eksik değerler -999 ile doldurulur; aksi halde tek bir eksik parça
    (örn. addr1) tüm uid'in NaN olmasına yol açar (string birleştirmede
    eksik değer, sonucun tamamını eksik yapar).
    """
    df = df.copy()
    transaction_day = df["TransactionDT"] // (60 * 60 * 24)
    d1n = df["D1"] - transaction_day

    df["uid"] = (
        df["card1"].fillna(-999).astype(str) + "_" +
        df["card2"].fillna(-999).astype(str) + "_" +
        df["card3"].fillna(-999).astype(str) + "_" +
        df["card5"].fillna(-999).astype(str) + "_" +
        df["addr1"].fillna(-999).astype(str) + "_" +
        d1n.fillna(-999).astype(str)
    )
    return df


def add_avg_transaction_amount(df: pd.DataFrame) -> pd.DataFrame:
    """Her uid için, o ana kadarki geçmiş işlemlerin ortalama tutarı.

    shift(1) mevcut işlemi hariç tutar, expanding().mean() o ana kadar
    birikmiş değerlerin ortalamasını alır. Bir uid'in ilk işleminde
    henüz geçmiş olmadığı için sonuç NaN'dır (beklenen davranış).
    """
    df = df.copy()
    df = df.sort_values(["uid", "TransactionDT"])
    df["avg_transaction_amount"] = (
        df.groupby("uid")["TransactionAmt"]
        .transform(lambda x: x.shift(1).expanding().mean())
    )
    return df


def add_amount_deviation_from_user(df: pd.DataFrame) -> pd.DataFrame:
    """İşlem tutarının, kullanıcının kendi ortalamasından yüzdesel sapması.

    Mutlak fark yerine yüzdesel sapma kullanılır; bu sayede farklı harcama
    seviyelerindeki kullanıcılar arasında karşılaştırılabilir bir ölçek elde
    edilir (örn. 50 TL sapma, 20 TL'lik bir ortalama için çok büyük, 2000
    TL'lik bir ortalama için önemsizdir).
    """
    df = df.copy()
    df["amount_deviation_from_user"] = (
        (df["TransactionAmt"] - df["avg_transaction_amount"]) / df["avg_transaction_amount"]
    )
    return df


def add_transaction_counts(df: pd.DataFrame) -> pd.DataFrame:
    """Her işlem için, aynı uid'in son 10 dakika ve son 24 saatteki işlem sayısı.

    closed="left" penceresi mevcut işlemi hariç tutar (leakage önleme).
    min_periods=0, pencerede hiç işlem yoksa sonucu NaN değil 0 yapar
    (0 işlem, geçerli ve anlamlı bir cevaptır).

    tie_breaker: aynı uid'in aynı saniyeye denk gelen birden fazla işlemi
    varsa, zaman tabanlı rolling bunları ayırt edemeyip satır kaybedebilir;
    her satıra grup-içi sırasına göre nanosaniyelik bir ofset eklenerek bu
    önlenir (10dk/24s pencere sınırlarını etkilemeyecek kadar küçük).
    """
    df = df.copy()
    df = df.sort_values(["uid", "TransactionDT"]).reset_index(drop=True)

    tie_breaker = pd.to_timedelta(df.groupby("uid").cumcount(), unit="ns")
    dt_index = pd.to_datetime(df["TransactionDT"], unit="s") + tie_breaker
    temp = df.set_index(dt_index)

    last_10min = (
        temp.groupby("uid")["TransactionID"]
        .rolling("10min", closed="left", min_periods=0)
        .count()
        .reset_index(level=0, drop=True)
    )
    last_24h = (
        temp.groupby("uid")["TransactionID"]
        .rolling("24h", closed="left", min_periods=0)
        .count()
        .reset_index(level=0, drop=True)
    )

    df["transactions_last_10min"] = last_10min.values
    df["transactions_last_24h"] = last_24h.values
    return df
