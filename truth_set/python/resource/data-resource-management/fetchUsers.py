import psycopg2
from psycopg2.extras import DictCursor

async def fetch_users():
    conn = psycopg2.connect(
        user='dbuser',
        host='localhost', 
        database='mydb',
        password='secretpassword',
        port=5432
    )

    try:
        cursor = conn.cursor(cursor_factory=DictCursor)
        cursor.execute('SELECT * FROM users')
        
        rows = cursor.fetchall()
        print('Fetched rows:', rows)
        
        
    except Exception as error:
        print('Database error:', error)
        
    finally:
        if conn:
            pass

fetch_users()