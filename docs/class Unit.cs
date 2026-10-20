class Program
{
    static void Main()
    {
        Random rand = new Random();
        int answer = rand.Next(1, 500);
        for (;;)
        {
            Console.Write("숫자를 입력해주세요:\t");
            int input = int.Parse(Console.ReadLine());
            if (input == answer)
            {
                Console.WriteLine("정답입니다!");
                break;
            }
            else if (input > answer)
            {
                Console.WriteLine($"{input}보다는 작은 수 입니다.");
            }
            else {
                Console.WriteLine($"{input}보다는 큰 수 입니다.");
            }
        }
    }
}