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

def add_new_device(df: pd.DataFrame) -> pd.DataFrame:
    """Bu işlemde kullanılan cihaz, bu uid için daha önce görülmüş mü?

    Yeni/tanınmayan bir cihazdan işlem yapmak fraud sinyali olabilir (hesap
    ele geçirmede saldırgan farklı bir cihaz kullanır). uid + zaman sırasına
    göre gruplanır; her (uid, DeviceInfo) çiftinin ilk görülüşü (cumcount==0)
    "yeni cihaz" (True) sayılır.

    DeviceInfo eksikse pandas bu satırları groupby'da hiçbir gruba dahil
    etmez ve cumcount() -1 döner (-1 == 0 -> False) — yani eksiklik otomatik
    olarak "bilinen cihaz" gibi görünür. Bu yanlış: eksikliği riskle
    karıştırmamak için bu satırlarda new_device açıkça NA'ya çevrilir.
    """
    df = df.copy()
    df = df.sort_values(["uid", "TransactionDT"])
    df["new_device"] = (
        df.groupby(["uid", "DeviceInfo"]).cumcount() == 0
    ).astype("boolean")
    df.loc[df["DeviceInfo"].isna(), "new_device"] = pd.NA
    return df


def add_new_location(df: pd.DataFrame) -> pd.DataFrame:
    """Bu işlemin addr1'i, bu kart ailesi için daha önce görülmüş mü?

    uid içinde addr1 zaten sabit bir bileşen (uid'in bir parçası) olduğu
    için, new_device'daki mantık doğrudan uid üzerinde uygulanamaz — aynı
    uid içinde addr1 hiçbir zaman değişemez (değişirse zaten farklı bir uid
    oluşur). Bunun yerine addr1 içermeyen, daha gevşek bir kart kimliği
    (card1+card2+card3+card5) kullanılır; böylece aynı kart ailesinin daha
    önce hiç işlem yapmadığı bir adresten işlem yapması yakalanabilir.

    new_device ile aynı kalıp: card_id + zaman sırasına göre gruplanıp her
    (card_id, addr1) çiftinin ilk görülüşü (cumcount==0) "yeni konum"
    sayılır. addr1 eksikse sonuç NA'ya çevrilir (eksiklik risk ile
    karıştırılmaz).
    """
    df = df.copy()
    df["card_id"] = (
        df["card1"].fillna(-999).astype(str) + "_" +
        df["card2"].fillna(-999).astype(str) + "_" +
        df["card3"].fillna(-999).astype(str) + "_" +
        df["card5"].fillna(-999).astype(str)
    )
    df = df.sort_values(["card_id", "TransactionDT"])
    df["new_location"] = (
        df.groupby(["card_id", "addr1"]).cumcount() == 0
    ).astype("boolean")
    df.loc[df["addr1"].isna(), "new_location"] = pd.NA
    return df


def add_distance_deviation_from_user(df: pd.DataFrame) -> pd.DataFrame:
    """İşlemin dist1'inin, kullanıcının kendi geçmiş dist1 ortalamasından
    yüzdesel sapması.

    dist1, işlem konumunun bir referans adresten (muhtemelen fatura adresi)
    uzaklığını temsil eder (Kaggle bu sütunun tam tanımını gizli tutuyor).
    Kullanıcının alışılmış uzaklık paterninden büyük bir sapma, işlemin
    farklı/beklenmedik bir konumdan yapıldığını işaret edebilir.

    avg_transaction_amount ile aynı kalıp: shift(1) mevcut işlemi hariç
    tutar, expanding().mean() o ana kadarki geçmişin ortalamasını alır.
    Bir uid'in ilk işleminde ya da dist1 hiç mevcut değilse (veri setinde
    dist1 satırların çoğunda eksik) sonuç NaN'dır.

    Not: avg_dist1 sıfırsa (kullanıcının geçmiş işlemleri hep dist1=0 ise)
    sapma sonsuza gider — bu, amount_deviation_from_user'daki aynı
    varsayımın (ortalama sıfır olmaz) burada geçerli olmayabileceği bir
    durum; modelleme aşamasında bu sütunun dağılımını kontrol etmek gerekir.
    """
    df = df.copy()
    df = df.sort_values(["uid", "TransactionDT"])
    df["avg_dist1"] = (
        df.groupby("uid")["dist1"]
        .transform(lambda x: x.shift(1).expanding().mean())
    )
    df["dist1_deviation_from_user"] = (
        (df["dist1"] - df["avg_dist1"]) / df["avg_dist1"]
    )
    return df


def add_time_since_last_transaction(df: pd.DataFrame) -> pd.DataFrame:
    """Bu uid'in bir önceki işleminden bu yana geçen süre (saniye).

    Proje listesindeki distance_from_last_transaction, veri setinde gerçek
    bir coğrafi mesafe (lat/long) bulunmadığı için zaman ekseninde
    yorumlanıyor: dist1/dist2 zaten dist1_deviation_from_user'da
    kullanıldığından, burada tekrar onları kullanmak bilgi tekrarına yol
    açardı. Art arda çok hızlı işlemler (hesap ele geçirmede sık görülen bir
    patern), transactions_last_10min/24h'nin veremediği kesinlikte bir
    "ardışıklık" sinyali sağlar.

    groupby("uid")["TransactionDT"].diff(), her satırı kendisinden bir
    önceki satırla (aynı uid içinde) karşılaştırır — doğası gereği yalnızca
    geçmişe bakar, leakage riski taşımaz (shift/expanding gerekmez, diff
    zaten "önceki satır" mantığıyla çalışır). Bir uid'in ilk işleminde
    önceki işlem olmadığı için sonuç NaN'dır (beklenen davranış).
    """
    df = df.copy()
    df = df.sort_values(["uid", "TransactionDT"])
    df["time_since_last_transaction"] = (
        df.groupby("uid")["TransactionDT"].diff()
    )
    return df

def add_merchant_risk(df: pd.DataFrame) -> pd.DataFrame:
    """Bu işlemin proxy-merchant'ının (ProductCD + P_emaildomain) geçmişteki
    fraud oranı, Bayesian smoothing ile.

    Veri setinde açık bir merchant_id yok; ProductCD + P_emaildomain en
    yakın proxy olarak kullanılıyor. isFraud etiketini kullandığı için
    (diğer özelliklerden farklı olarak) SADECE etiketli eğitim verisinde
    (train_transaction) çalışır, test/canlı veride isFraud yok — bu yüzden
    canlı bir sistemde bu, eğitim setinden üretilip donmuş (frozen) bir
    lookup tablosu olarak servis edilmeli, her istekte yeniden hesaplanamaz.

    İki ayrı expanding hesap gerekiyor:
    1. global_expanding_fraud_rate: TÜM veri setinin zamana göre kümülatif
       fraud oranı (smoothing'in "prior"ı/çekim noktası). Bunu da expanding
       yaparak (tüm veriden sabit bir ortalama almak yerine) zaman-bazlı
       saflığı koruyoruz.
    2. Her merchant_id için ayrı ayrı kümülatif fraud toplamı ve işlem
       sayısı (smoothing formülü ikisini ayrı ayrı istiyor, tek bir mean
       yetmiyor).

    Bir merchant_id'nin ilk görüldüğü satırda toplam NaN çıkar (shift
    sonrası boş pencere) — fillna(0) ile düzeltilir, aksi halde formül o
    satırlarda NaN üretir. (Küçük bir istisna: veri setinin en baştaki tek
    satırında global_expanding_fraud_rate de NaN'dır, bu tek satır için
    merchant_risk de NaN kalır — ihmal edilebilir düzeyde, ayrıca ele
    alınmadı.)
    """
    df = df.copy()

    df["merchant_id"] = (
        df["ProductCD"].fillna("missing").astype(str) + "_" +
        df["P_emaildomain"].fillna("missing").astype(str)
    )

    df = df.sort_values("TransactionDT")
    df["global_expanding_fraud_rate"] = (
        df["isFraud"].shift(1).expanding().mean()
    )

    df = df.sort_values(["merchant_id", "TransactionDT"])
    group_fraud_sum = (
        df.groupby("merchant_id")["isFraud"]
        .transform(lambda x: x.shift(1).expanding().sum())
        .fillna(0)
    )
    group_count = (
        df.groupby("merchant_id")["isFraud"]
        .transform(lambda x: x.shift(1).expanding().count())
    )

    m = 100
    df["merchant_risk"] = (
        (group_fraud_sum + m * df["global_expanding_fraud_rate"]) / (group_count + m)
    )

    return df

